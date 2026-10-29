# Task 03 — Phase 2: Provisioner service (`provisioner` module)

> Prerequisites: `00-overview`, `02-backend-dummy` (sanitizer + naming conventions).
> Timebox 2–3 days. Adds `provisioner` module to parent pom.

## Objective

Spring Boot service that provisions Postgres DBs/schemas/roles per customer/tenant,
maintains a registry, installs `pgbouncer_get_auth` in each DB, and exposes a REST API
for the React UI. Admin endpoints secured by a simple token header (demo).

## Module & dependencies

Add `provisioner` to parent `pom.xml` modules. Dependencies:
- `spring-boot-starter-web`
- `spring-boot-starter-jdbc`
- `spring-boot-starter-actuator`
- `postgresql`
- Lombok

Dockerfile: multi-stage Maven build → JRE; expose 8080.

## Environment / config

- `POSTGRES_HOST` (default cluster DNS `...-postgresql`)
- `POSTGRES_PORT` (default 5432)
- `POSTGRES_USER` (default `postgres`)
- `POSTGRES_PASSWORD` (from secret)
- `BACKEND_SERVICES` (comma list, e.g. `order-service,inventory-service`)
- Service→table map (e.g. from env or config; must match `.Values.backends`).
- `ADMIN_TOKEN` (simple header token for demo endpoints)
- `CORS_ORIGINS` (frontend ingress host)

## Sanitization (duplicated from backend)

Same `Sanitizer` as task 02: lowercase, strip non-`[a-z0-9]`, validate derived names
(`cust_<c>`, `tenant_<t>`, `app_<t>_<service>`) ≤ 63, validate customer/tenant IDs
`[a-z0-9_]+` on input.

## Registry schema (in `postgres` DB, schema `registry`)

Created idempotently on startup:
```sql
CREATE SCHEMA IF NOT EXISTS registry;
CREATE TABLE IF NOT EXISTS registry.customers (
  id TEXT PRIMARY KEY,
  name TEXT,
  db_name TEXT,
  created_at TIMESTAMPTZ
);
CREATE TABLE IF NOT EXISTS registry.tenants (
  id TEXT PRIMARY KEY,
  customer_id TEXT REFERENCES registry.customers(id),
  schema_name TEXT,
  created_at TIMESTAMPTZ
);
```
Tenant id globally unique (PK) → a tenant name cannot be reused across customers.

## Postgres admin connection

JDBC to `postgres` superuser: `jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/postgres`.
Use a single admin `JdbcTemplate`/`DataSource` (auto-configured normally here, since
provisioner talks to one DB). Credentials from env.

## Provisioning logic

### Startup
- Ensure `registry` schema/tables exist.
- Load configured backend services + table map.

### `POST /api/customers {name}`
1. Validate/sanitize customer id/name → `cust_<c>`.
2. Check uniqueness in registry; reject duplicates.
3. Connect as superuser; `CREATE DATABASE cust_<c>` (if not exists; idempotent).
4. Connect to `cust_<c>`; install `pgbouncer_get_auth` function (owned by postgres,
   security definer) + grants:
   - `CREATE ROLE pgbouncer_auth ...` if not exists? (already exists cluster-wide from
     init script; in customer DB just grant CONNECT)
   - `GRANT CONNECT ON DATABASE cust_<c> TO pgbouncer_auth;`
   - create/recreate `public.pgbouncer_get_auth(p_usename TEXT)` returning
     `(usename name, passwd text)` from `pg_shadow`, SECURITY DEFINER, search_path pg_catalog.
   - `REVOKE ALL ... FROM PUBLIC; GRANT EXECUTE ... TO pgbouncer_auth;`
5. Insert registry row; return customerId.

   Idempotency: if DB exists, skip creation but ensure function/grants.

### `POST /api/customers/{c}/tenants {name}`
1. Validate customer exists; validate/sanitize tenant id → `tenant_<t>`; globally unique
   (registry PK) → reject if already exists.
2. For each configured backend service:
   - role name `app_<t>_<service>` (sanitized service).
   - validate derived name ≤ 63; reject if over.
   - `CREATE ROLE app_<t>_<service> LOGIN PASSWORD '<t>'` (password = tenant id).
   - `ALTER ROLE app_<t>_<service> SET search_path TO tenant_<t>, public;`
   - create schema `tenant_<t>` (once, shared across services) if not exists.
   - `GRANT USAGE, CREATE ON SCHEMA tenant_<t> TO app_<t>_<service>;`
   - create service table (e.g. `orders`, `inventory`) owned by that role:
     ```sql
     CREATE TABLE IF NOT EXISTS tenant_<t>.<table> (
       id UUID PRIMARY KEY,
       name TEXT NOT NULL,
       created_at TIMESTAMPTZ NOT NULL DEFAULT now()
     );
     ALTER TABLE tenant_<t>.<table> OWNER TO app_<t>_<service>;
     ```
   - `GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA tenant_<t> TO ...`
     (per service role; at minimum own table)
   - `ALTER DEFAULT PRIVILEGES IN SCHEMA tenant_<t> GRANT ...`
   - Ensure pgbouncer_auth has CONNECT on this DB (already) and EXECUTE on function.
3. Insert registry tenant row; return tenantId.

   DDL built with double-quoted identifiers from validated/sanitized inputs.

### `GET /api/customers`
### `GET /api/customers/{c}/tenants`
### `DELETE` (optional) for cleanup — customer DB drop / tenant schema + roles drop.
### `POST /api/seed` → create N customers × M tenants for load demo (params in body).
### Cleanup endpoints (in scope): `DELETE /api/customers/{c}` and
`DELETE /api/customers/{c}/tenants/{t}` (idempotent, admin-token guarded).

## Security (admin token)

Simple filter: require `X-Admin-Token` header matching `ADMIN_TOKEN` for `/api/*` (except
health). Demo only.

## CORS

Enable CORS for `CORS_ORIGINS` (frontend ingress host) on `/api/**`.

## Actuator

`/actuator/health`, `/actuator/prometheus`. `management.health.db.enabled` can be true
here (provisioner has a real DataSource).

## Tests

- Integration (Testcontainers Postgres): create customer → DB exists + function installed;
  create tenant → schema/roles/tables exist; role password works; search_path default
  correct; pgbouncer_auth can execute function.
- Unit: sanitizer validation, idempotency of create-if-not-exists.

## Acceptance criteria

- `mvn -pl provisioner -am package` builds.
- Docker image builds.
- Running against a Postgres, `POST /api/customers {"name":"acme"}` creates `cust_acme`
  with function installed.
- `POST /api/customers/acme/tenants {"name":"t1"}` creates schema `tenant_t1`, role
  `app_t1_orderservice`, table `orders` owned by that role.
- Registry rows present.
- Role can connect to `cust_acme` and `SELECT current_schema()` returns `tenant_t1`.

## Risks / notes

- `CREATE DATABASE` cannot run inside a transaction; use a separate connection/autocommit.
- Cross-DB connections: admin connects to `postgres` for CREATE DATABASE, then to
  `cust_<c>` for schema/role DDL.
- Idempotency for re-runs (seed, retries).
- Password = tenant id; ensure tenant id sanitized matches (customer/tenant IDs already
  canonical, so password equals raw tenant id).
- Service names sanitized for role names and table ownership; table names come from
  config map (already simple identifiers, but validate).
