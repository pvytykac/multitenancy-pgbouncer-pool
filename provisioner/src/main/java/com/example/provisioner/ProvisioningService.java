package com.example.provisioner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

@Service
public class ProvisioningService {

    private final JdbcTemplate adminJdbc;
    private final String host;
    private final String port;
    private final String user;
    private final String password;
    private final ProvisionerProperties props;

    public ProvisioningService(JdbcTemplate adminJdbc,
                               @Value("${POSTGRES_HOST:postgres}") String host,
                               @Value("${POSTGRES_PORT:5432}") String port,
                               @Value("${POSTGRES_USER:postgres}") String user,
                               @Value("${POSTGRES_PASSWORD:}") String password,
                               ProvisionerProperties props) {
        this.adminJdbc = adminJdbc;
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.props = props;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        adminJdbc.execute("CREATE SCHEMA IF NOT EXISTS registry");
        adminJdbc.execute("CREATE TABLE IF NOT EXISTS registry.customers (" +
                "id TEXT PRIMARY KEY, name TEXT, db_name TEXT, created_at TIMESTAMPTZ)");
        adminJdbc.execute("CREATE TABLE IF NOT EXISTS registry.tenants (" +
                "id TEXT PRIMARY KEY, customer_id TEXT REFERENCES registry.customers(id), " +
                "schema_name TEXT, created_at TIMESTAMPTZ)");
        ensurePgbouncerRole();
    }

    public List<String> services() {
        return props.getServices();
    }

    private Map<String, String> tables() {
        return props.getTables();
    }

    private void ensurePgbouncerRole() {
        adminJdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pgbouncer_auth') " +
                "THEN CREATE ROLE pgbouncer_auth LOGIN; END IF; END $$;");
    }

    public String createCustomer(String rawName) {
        Sanitizer.validateCustomerId(rawName);
        String customerId = Sanitizer.sanitize(rawName);
        String dbName = "cust_" + customerId;
        Sanitizer.validateLength(dbName);

        Integer count = adminJdbc.queryForObject(
                "SELECT count(*) FROM registry.customers WHERE id = ?", Integer.class, customerId);
        if (count != null && count > 0) {
            throw new DuplicateResourceException("customer already exists: " + customerId);
        }

        createDatabase(dbName);
        installAuth(dbName);

        adminJdbc.update(
                "INSERT INTO registry.customers (id, name, db_name, created_at) VALUES (?,?,?,now())",
                customerId, rawName, dbName);
        return customerId;
    }

    public void createDatabase(String dbName) {
        Sanitizer.validateIdentifier(dbName, "dbName");
        Boolean exists = adminJdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = ?)", Boolean.class, dbName);
        if (Boolean.TRUE.equals(exists)) {
            return;
        }
        adminJdbc.execute("CREATE DATABASE \"" + dbName + "\"");
    }

    private void installAuth(String dbName) {
        ensurePgbouncerRole();
        try {
            adminJdbc.execute("GRANT CONNECT ON DATABASE \"" + dbName + "\" TO pgbouncer_auth");
        } catch (Exception ignored) {
        }
        JdbcTemplate db = jdbcFor(dbName);
        db.execute("CREATE OR REPLACE FUNCTION public.pgbouncer_get_auth(p_usename TEXT) " +
                "RETURNS TABLE (usename name, passwd text) " +
                "LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog " +
                "AS $$ SELECT usename, passwd FROM pg_shadow WHERE usename = p_usename; $$");
        db.execute("REVOKE ALL ON FUNCTION public.pgbouncer_get_auth(TEXT) FROM PUBLIC");
        db.execute("GRANT EXECUTE ON FUNCTION public.pgbouncer_get_auth(TEXT) TO pgbouncer_auth");
        try {
            db.execute("GRANT USAGE ON SCHEMA public TO pgbouncer_auth");
        } catch (Exception ignored) {
        }
    }

    public String createTenant(String customerId, String rawTenantName) {
        Sanitizer.validateCustomerId(customerId);
        Sanitizer.validateTenantId(rawTenantName);
        String custId = Sanitizer.sanitize(customerId);
        String tenantId = Sanitizer.sanitize(rawTenantName);

        Integer c = adminJdbc.queryForObject(
                "SELECT count(*) FROM registry.customers WHERE id = ?", Integer.class, custId);
        if (c == null || c == 0) {
            throw new NotFoundException("customer not found: " + custId);
        }

        Integer t = adminJdbc.queryForObject(
                "SELECT count(*) FROM registry.tenants WHERE id = ?", Integer.class, tenantId);
        if (t != null && t > 0) {
            throw new DuplicateResourceException("tenant already exists: " + tenantId);
        }

        String dbName = adminJdbc.queryForObject(
                "SELECT db_name FROM registry.customers WHERE id = ?", String.class, custId);

        String schemaName = "tenant_" + tenantId;
        Sanitizer.validateLength(schemaName);

        JdbcTemplate db = jdbcFor(dbName);
        ensurePgbouncerRole();
        try {
            adminJdbc.execute("GRANT CONNECT ON DATABASE \"" + dbName + "\" TO pgbouncer_auth");
        } catch (Exception ignored) {
        }

        db.execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");

        for (String service : services()) {
            String sanitizedService = Sanitizer.sanitizeServiceName(service);
            String roleName = "app_" + tenantId + "_" + sanitizedService;
            Sanitizer.validateLength(roleName);

            String tableName = tables().get(service);
            if (tableName == null || tableName.isBlank()) {
                tableName = sanitizedService;
            }
            Sanitizer.validateIdentifier(tableName, "tableName");

            db.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + roleName + "') " +
                    "THEN CREATE ROLE \"" + roleName + "\" LOGIN PASSWORD '" + rawTenantName + "'; END IF; END $$;");
            db.execute("ALTER ROLE \"" + roleName + "\" PASSWORD '" + rawTenantName + "'");
            db.execute("ALTER ROLE \"" + roleName + "\" SET search_path TO \"" + schemaName + "\", public");
            db.execute("GRANT USAGE, CREATE ON SCHEMA \"" + schemaName + "\" TO \"" + roleName + "\"");

            db.execute("CREATE TABLE IF NOT EXISTS \"" + schemaName + "\".\"" + tableName + "\" " +
                    "(id UUID PRIMARY KEY, name TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            db.execute("ALTER TABLE \"" + schemaName + "\".\"" + tableName + "\" OWNER TO \"" + roleName + "\"");
            db.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA \"" + schemaName + "\" TO \"" + roleName + "\"");
            db.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA \"" + schemaName + "\" " +
                    "GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO \"" + roleName + "\"");
        }

        installAuth(dbName);

        adminJdbc.update(
                "INSERT INTO registry.tenants (id, customer_id, schema_name, created_at) VALUES (?,?,?,now())",
                tenantId, custId, schemaName);
        return tenantId;
    }

    public List<Map<String, Object>> listCustomers() {
        return adminJdbc.queryForList("SELECT id, name, db_name, created_at FROM registry.customers ORDER BY created_at");
    }

    public List<Map<String, Object>> listTenants(String customerId) {
        Sanitizer.validateCustomerId(customerId);
        String custId = Sanitizer.sanitize(customerId);
        return adminJdbc.queryForList(
                "SELECT id, customer_id, schema_name, created_at FROM registry.tenants WHERE customer_id = ? ORDER BY created_at",
                custId);
    }

    public void deleteTenant(String customerId, String rawTenantName) {
        Sanitizer.validateCustomerId(customerId);
        Sanitizer.validateTenantId(rawTenantName);
        String custId = Sanitizer.sanitize(customerId);
        String tenantId = Sanitizer.sanitize(rawTenantName);
        String schemaName = "tenant_" + tenantId;

        String dbName;
        try {
            dbName = adminJdbc.queryForObject(
                    "SELECT db_name FROM registry.customers WHERE id = ?", String.class, custId);
        } catch (Exception e) {
            return;
        }

        JdbcTemplate db = jdbcFor(dbName);
        try {
            db.execute("DROP SCHEMA IF EXISTS \"" + schemaName + "\" CASCADE");
        } catch (Exception ignored) {
        }
        for (String service : services()) {
            String sanitizedService = Sanitizer.sanitizeServiceName(service);
            String roleName = "app_" + tenantId + "_" + sanitizedService;
            try {
                adminJdbc.execute("DROP ROLE IF EXISTS \"" + roleName + "\"");
            } catch (Exception ignored) {
            }
        }
        adminJdbc.update("DELETE FROM registry.tenants WHERE id = ?", tenantId);
    }

    public void deleteCustomer(String customerId) {
        Sanitizer.validateCustomerId(customerId);
        String custId = Sanitizer.sanitize(customerId);

        List<Map<String, Object>> tenants = listTenants(custId);
        for (Map<String, Object> t : tenants) {
            String tid = String.valueOf(t.get("id"));
            for (String service : services()) {
                String sanitizedService = Sanitizer.sanitizeServiceName(service);
                String roleName = "app_" + tid + "_" + sanitizedService;
                try {
                    adminJdbc.execute("DROP ROLE IF EXISTS \"" + roleName + "\"");
                } catch (Exception ignored) {
                }
            }
        }
        adminJdbc.update("DELETE FROM registry.tenants WHERE customer_id = ?", custId);

        String dbName;
        try {
            dbName = adminJdbc.queryForObject(
                    "SELECT db_name FROM registry.customers WHERE id = ?", String.class, custId);
        } catch (Exception e) {
            adminJdbc.update("DELETE FROM registry.customers WHERE id = ?", custId);
            return;
        }

        try {
            adminJdbc.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                    "WHERE datname = '" + dbName + "' AND pid <> pg_backend_pid()");
        } catch (Exception ignored) {
        }
        try {
            adminJdbc.execute("DROP DATABASE IF EXISTS \"" + dbName + "\"");
        } catch (Exception ignored) {
        }
        adminJdbc.update("DELETE FROM registry.customers WHERE id = ?", custId);
    }

    public Map<String, Object> seed(int customers, int tenantsPerCustomer) {
        int createdCustomers = 0;
        int createdTenants = 0;
        for (int i = 1; i <= customers; i++) {
            String cid = "seedc" + i;
            try {
                createCustomer(cid);
                createdCustomers++;
            } catch (DuplicateResourceException ignored) {
            }
            for (int j = 1; j <= tenantsPerCustomer; j++) {
                String tid = "seedt" + i + "_" + j;
                try {
                    createTenant(cid, tid);
                    createdTenants++;
                } catch (DuplicateResourceException ignored) {
                }
            }
        }
        return Map.of("customers", createdCustomers, "tenants", createdTenants);
    }

    private JdbcTemplate jdbcFor(String dbName) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl("jdbc:postgresql://" + host + ":" + port + "/" + dbName);
        ds.setUsername(user);
        ds.setPassword(password);
        return new JdbcTemplate(ds);
    }
}
