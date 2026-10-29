package com.example.backenddummy;

public final class TenantCredentialService {

    private TenantCredentialService() {
    }

    public record Credentials(String username, String password, String dbName, String schema, String service) {
    }

    public static Credentials derive(String customerId, String tenantId, String serviceName) {
        Sanitizer.validateCustomerId(customerId);
        Sanitizer.validateTenantId(tenantId);

        String sanitizedCustomer = Sanitizer.sanitize(customerId);
        String sanitizedTenant = Sanitizer.sanitize(tenantId);
        String sanitizedService = Sanitizer.sanitizeServiceName(serviceName);

        String username = "app_" + sanitizedTenant + "_" + sanitizedService;
        String password = tenantId;
        String dbName = "cust_" + sanitizedCustomer;
        String schema = "tenant_" + sanitizedTenant;

        Sanitizer.validateLength(dbName, 63);
        Sanitizer.validateLength(schema, 63);
        Sanitizer.validateLength(username, 63);

        return new Credentials(username, password, dbName, schema, serviceName);
    }

    public static Credentials deriveCredentials(String customerId, String tenantId, String serviceName) {
        return derive(customerId, tenantId, serviceName);
    }

    public static Credentials deriveTenantCredentials(String customerId, String tenantId, String serviceName) {
        return derive(customerId, tenantId, serviceName);
    }
}
