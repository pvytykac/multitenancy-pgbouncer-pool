# DB Pooling Demo — PgBouncer + Per-Tenant Isolation

> Status: **Draft spec.** Nothing implemented yet.
> Theme: Replace per-tenant/per-schema Hikari pools in Spring Boot microservices with
> external PgBouncer pooling, while preserving per-customer DB + per-tenant schema
> isolation and per-tenant DB users. Includes a runnable demo deployment.

## 1. Goals

- Reduce connection count from `microservices × replicas × tenants × schemas` to a
  bounded `0–5 connections per (customer DB, tenant user)` maintained by PgBouncer.
- Microservices keep many tiny keepalive Hikari pools pointed at PgBouncer (one per
  `(customer DB, tenant user)`). The target database is selected per connection over the
  wire (JDBC `dbname`): PgBouncer's wildcard `*` database entry passes the
  client-supplied `dbname` through to Postgres, while each backend JVM holds a separate
  small pool per `(DB, user)`. PgBouncer collapses pools with the same `(dbname, user)`
  across JVMs/replicas into one server pool.
- Tenant schema isolation is provided by **DB users scoped to their schema**
  (`ALTER ROLE ... SET search_path`), so PgBouncer transaction pooling stays safe.
  Tenant credentials are deterministic (username `app_<tenant>_<service>`, password
  equals the tenant id) and derived locally by the backend. Credentials are
  deliberately simplified for the demo (password = tenant id); not production-grade.
- Ship a demo: Helm chart deploying Postgres (Bitnami), PgBouncer (Bitnami), `x` dummy
  Spring Boot backends with `n` replicas each, a provisioner service + simple UI, and
  observability wiring (PgBouncer exporter + Spring Boot Actuator/Prometheus).
- Reuse the cluster's existing OTel/Prometheus/Grafana; do not deploy observability
  infra in the chart (only ServiceMonitors / scrape annotations / dashboards).

## 2. Non-goals

- Production HA, multi-region, backups/PITR.
- Application-level multi-tenancy beyond DB/schema isolation.
- Migrating existing production microservices (demo only).

## 3. Current vs. desired connection math

Current (per microservice JVM):
- One Hikari pool per `(customer DB × tenant schema)`, `minIdle ≥ 1`, `maxPoolSize ≥ 10`.
- Cost: `10 services × 3 replicas × 200 customers × tenants × schemas` ≈ tens of
  thousands of idle connections; Postgres `max_connections` ceiling.

Desired:
- Client (Hikari): `minIdle 1` (keepalive), `maxPoolSize 2` per `(DB, user)`, pointed
  at PgBouncer.
- PgBouncer: `default_pool_size = 5`, `min_pool_size = 0`, `reserve_pool_size = 0`,
  `pool_mode = transaction`. One backend pool per `(actual dbname, user)` → `0–5`
  connections per tenant.
- Net: bounded and lazy on both sides.

## 4. Architecture

```
┌──────────────┐  HTTP   ┌─────────────────┐
│   Demo UI    │────────►│  Provisioner    │
│  (React SPA) │         │  (Spring Boot)  │
│              │         └────┬────┬───────┘
└──────────────┘              │    │
                              │    │ creates DBs/schemas/roles
                              │    │ creds derived locally
                              ▼    ▼
┌─────────────────────────────────────────────────────────────┐
│                       Kubernetes                            │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐             │
│  │ backend ×N │  │ backend ×N │  │ backend ×N │  (x services)│
│  │  (replicas)│  │            │  │            │             │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘             │
│        └───────────────┼───────────────┘                     │
│                        ▼                                     │
│                 ┌──────────────┐                              │
│                 │  PgBouncer   │  (bitnami/pgbouncer)         │
│                 │  :6432       │                              │
│                 └──────┬───────┘                              │
│                        ▼                                     │
│                 ┌──────────────┐                              │
│                 │  Postgres    │  (bitnami/postgresql)        │
│                 │  :5432       │                              │
│                 └──────────────┘                              │
└─────────────────────────────────────────────────────────────┘
```

Data flow (request to a backend for tenant T of customer C):
1. Ingress → backend pod; request carries `X-Customer-Id`, `X-Tenant-Id` (or path).
2. Backend sets `TenantContext` (ThreadLocal).
3. Backend derives tenant credentials locally: username `app_<tenant>_<service>`,
   password = tenant id (simplified for the demo).
4. Backend obtains/creates a small Hikari pool keyed by `(customer, tenant, service)` →
   `(dbName=cust_<c>, user)` pointing
   at `pgbouncer:6432`, borrows a connection, runs query, returns connection.
5. PgBouncer routes the client connection to backend Postgres using the `dbname` from
   the startup packet (wildcard `*` database entry), authenticates the user via
   `auth_query`, and reuses/creates a server connection in that `(db, user)` pool.

## 5. Data model — DBs, schemas, users

- **Customer** → Postgres database `cust_<customerId>` (lowercased, sanitized). Customer
  IDs are globally unique because database names are cluster-global.
- **Tenant** → schema `tenant_<tenantId>` inside the customer DB. Tenant IDs are **globally
  unique** (enforced by `registry.tenants.id` PK) because Postgres roles are
  cluster-global — `app_<tenant>_<service>` would collide across customers otherwise; the
  same tenant name therefore cannot be reused across customers.
- **User** → Postgres role `app_<tenantId>_<service>` (one per tenant × microservice) with:
  - `LOGIN PASSWORD '<tenantId>'` (simplified: password equals tenant id; demo only)
  - `ALTER ROLE ... SET search_path TO tenant_<tenantId>, public`
  - `GRANT USAGE, CREATE ON SCHEMA tenant_<tenantId> TO ...`
  - ownership of that service's demo table(s) in the tenant schema
  - `GRANT SELECT/INSERT/... ON ALL TABLES IN SCHEMA ...` (plus `ALTER DEFAULT PRIVILEGES`
    so future tables are covered)
- **PgBouncer auth user** → role `pgbouncer_auth` with `CONNECT` on all DBs and
  `EXECUTE` on `pgbouncer_get_auth(...)`, used by PgBouncer `auth_query`. Created in the
  `postgres` DB by the init script and recreated by the provisioner in every customer DB.
- **Provisioner admin** → Postgres superuser (bitnami `postgres` user) for DDL;
  stored in K8s secret, mounted into provisioner.

Naming constraints: sanitize identifiers to `[a-z0-9_]+`. Sanitization lowercases the
value and strips hyphens (and any other non-`[a-z0-9]` characters); the fixed tokens
(`app`, tenant, service) are joined with `_` as the delimiter (e.g. `order-service` →
`orderservice`, role `app_t1_orderservice`). Enforce the Postgres 63-char limit for
database, schema, and role names by **rejecting** over-long inputs (not truncating, to
avoid collisions); the limit is checked against the *derived* names
(`cust_<c>`, `tenant_<t>`, `app_<t>_<service>`), not just the raw inputs. Customer and
tenant IDs are validated to `[a-z0-9_]+` on input (already canonical, lowercase), so the
raw and sanitized forms match; the derived password (tenant id) therefore matches the
role password exactly. Service names are the exception: they may contain hyphens (and
other characters) and are sanitized before use in identifiers and credential derivation.
The same sanitization is duplicated in the backend and the provisioner (no shared module).

## 6. PgBouncer configuration

Bitnami chart values, `configuration` override:

```ini
[databases]
* = host={{ .Release.Name }}-postgresql port=5432

[pgbouncer]
listen_addr = 0.0.0.0
listen_port = 6432
auth_type = scram-sha-256
auth_file = /etc/pgbouncer/userlist.txt
auth_query = SELECT * FROM public.pgbouncer_get_auth($1)
auth_user = pgbouncer_auth
pool_mode = transaction
default_pool_size = 5
min_pool_size = 0
reserve_pool_size = 0
max_client_conn = 10000
max_db_connections = 0
server_idle_timeout = 600
server_lifetime = 3600
server_reset_query = DISCARD ALL
ignore_startup_parameters = extra_float_digits,client_encoding
admin_users = pgbouncer_admin
stats_users = pgbouncer_exporter
```

Notes:
- Wildcard `*` makes the client-supplied `dbname` flow through to Postgres ("DB comes over
  the wire"); backends therefore select a per-`(DB, user)` Hikari pool by tenant while
  PgBouncer collapses pools with the same `(dbname, user)` across JVMs/replicas onto one
  server pool.
- `auth_query` removes the need to ship a static `userlist.txt` for dynamically created
  tenants; only `pgbouncer_auth` (and admin/exporter) need entries. The auth function must
  exist in *every* database (including each customer DB) — see §8.
- `pool_mode = transaction` + role-default `search_path` keeps search_path correct across
  pooled server connections (`DISCARD ALL` resets to role default).
- Backend prepared statements must be disabled on the JDBC side (see §7):
  `prepareThreshold=0&preparedStatementCacheQueries=0`.
- Transaction-pooling limitations (document for backend authors): no session-level state,
  no advisory locks, no `LISTEN/NOTIFY`, no temp tables, no `SET` outside of
  transaction. `ignore_startup_parameters` covers `extra_float_digits` and
  `client_encoding` sent by the JDBC driver.

Postgres side (bitnami init script):
```sql
CREATE ROLE pgbouncer_auth WITH LOGIN PASSWORD '...' NOINHERIT;
GRANT CONNECT ON DATABASE postgres TO pgbouncer_auth;
CREATE OR REPLACE FUNCTION public.pgbouncer_get_auth(p_usename TEXT)
RETURNS TABLE(usename name, passwd text)
LANGUAGE sql SECURITY DEFINER
SET search_path = pg_catalog
AS $$
  SELECT usename, passwd::text FROM pg_shadow WHERE usename = p_usename;
$$;
REVOKE ALL ON FUNCTION public.pgbouncer_get_auth(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.pgbouncer_get_auth(TEXT) TO pgbouncer_auth;
```
`pg_shadow` is superuser-only in PG 14+, so auth goes through the security-definer
function above (owned by the `postgres` superuser, `SECURITY DEFINER`,
`SET search_path = pg_catalog`). The function is created in `postgres` here and must be
recreated in every customer DB by the provisioner, which also grants
`CONNECT ON DATABASE cust_<c> TO pgbouncer_auth` and `EXECUTE ON FUNCTION
public.pgbouncer_get_auth(TEXT) TO pgbouncer_auth`.

## 7. Spring Boot backend design

Module `backend-dummy` (one image, differentiated by `SERVICE_NAME` env).

Dependencies: `spring-boot-starter-web`, `spring-boot-starter-jdbc` (JdbcTemplate
preferred over `spring-boot-starter-data-jpa` for the demo; see `application.yml`),
`spring-boot-starter-actuator`, `micrometer-registry-prometheus`, `postgresql`,
`hikari`. Spring Boot 4.1.2. Lombok allowed per repo conventions. Credentials are
derived locally (no external secret store in the demo).

Key components:

- `TenantContext` (ThreadLocal) holding `customerId`, `tenantId`, `dbName`, `schema`,
  `service`.
- Env/config: `SERVICE_NAME`, `TABLE_NAME` (target table for CRUD), `PGBouncer_HOST`,
  `PGBouncer_PORT`. Identifiers are sanitized per §5 (duplicated logic, no shared module).
- `TenantFilter` (OncePerRequestFilter) parses `X-Customer-Id` / `X-Tenant-Id` (or
  path vars) into `TenantContext`; clears in finally.
- `TenantCredentialService`: derives credentials locally — username
  `app_<tenant>_<service>`, password = tenant id (simplified for the demo).
- `TenantRoutingDataSource extends AbstractRoutingDataSource` (marked `@Primary`):
  - lookup key = composite of `customer`, `tenant`, `service` (must include service, since
    the role/user differs per service). Use a composite key type (record), not string
    concatenation, to avoid delimiter collisions.
  - `determineCurrentLookupKey()` returns the key or `null` when unset; `determineTargetDataSource()`
    is overridden to avoid throwing for non-tenant requests (actuator, probes) — non-tenant
    callers must not touch the DataSource. With no default target set, any caller that does
    invoke `getConnection()` without a tenant context gets a clear `IllegalStateException`;
    keep `management.health.db.enabled=false` and avoid JPA startup connection validation
    (see `application.yml` below) so no startup path touches it.
  - `resolveDataSource(key)` lazily builds a Hikari pool, guarded against concurrent
    creation for the same key (e.g. `ConcurrentHashMap.computeIfAbsent` with a lock) so
    duplicate pools are not created under load:
    ```
    jdbc:postgresql://${PGBouncer_HOST:pgbouncer}:${PGBouncer_PORT:6432}/<dbName>
      ?prepareThreshold=0&preparedStatementCacheQueries=0&ApplicationName=<service>
    username = derived user
    password = derived password
    minimumIdle = 1 (keepalive warmth)
    maximumPoolSize = 2
    idleTimeout = 60000
    maxLifetime = 1800000   # periodic recycle of client-side (PgBouncer-facing) connections
    connectionTimeout = 30000
    poolName = <service>-<customer>-<tenant>
    ```
  - Each created pool is registered with the `MeterRegistry` (Micrometer Hikari binder)
    so `hikaricp_*` metrics are published.
  - No pool eviction in the demo: pools are small (max 2) and bounded by JVM memory;
    acceptable for demo scale.
- `TenantRepository` / endpoints: `GET /api/v1/whoami` runs `SELECT 1` + reads
  `current_schema()`, `current_user` and returns them; `GET/POST /api/v1/items` CRUDs
  the service's own table (e.g. `orders` for order-service, `inventory` for
  inventory-service) in the tenant schema to prove isolation and ownership.
- `application.yml`:
  - disable Spring's auto `DataSource` (`exclude = DataSourceAutoConfiguration.class`)
    since the routing source is created programmatically (routing DS is `@Primary`).
  - Prefer `JdbcTemplate` over JPA in the demo (no startup connection validation); if
    JPA is used instead, pin `spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect`
    and set `spring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults=false` so the
    `EntityManagerFactory` is built without touching the (tenant-less) routing DataSource,
    and exclude `HibernateJpaAutoConfiguration` only when JPA is not used.
  - `spring.jpa.hibernate.ddl-auto=none` (tables are pre-created/owned by the provisioner;
    backends must not issue DDL).
  - `management.endpoints.web.exposure.include=health,info,prometheus`
  - `management.health.db.enabled=false` (routing DS has no default target; liveness is
    served by the actuator endpoint itself)
  - `server.port=8080`

Endpoints:
- `GET /actuator/health` (used by K8s probes), `/actuator/prometheus`
- `GET /api/v1/whoami` → `{service, customer, tenant, db, user, schema}`
- `GET/POST /api/v1/items` → demo CRUD against the service's tenant table.

## 8. Provisioner + UI

Module `provisioner` (Spring Boot). Responsibilities:
- Admin endpoints (secured by simple token header for demo):
  - `POST /api/customers {name}` → create DB `cust_<c>` (as the `postgres` superuser),
    install the `pgbouncer_get_auth` function (owned by `postgres`, security definer) +
    grants in that DB, including `GRANT CONNECT ON DATABASE cust_<c> TO pgbouncer_auth`,
    store metadata in the `registry` schema/table in `postgres`, return customerId.
  - `POST /api/customers/{c}/tenants {name}` → create schema `tenant_<t>`, create one
    role per configured backend service (`app_<t>_<service>`, sanitized per §5) with
    password = tenant id, set `search_path`, grant schema usage/create, create each
    service's own table (from the service→table map, e.g. `orders`, `inventory`) owned
    by that service's role, apply default privileges, register. Tenant IDs are globally
    unique (registry PK).
  - `GET /api/customers`, `GET /api/customers/{c}/tenants`.
  - `DELETE` (optional) for cleanup.
  - `POST /api/seed` → create N customers × M tenants for load demo.
- DB admin via JDBC to the `postgres` superuser (bitnami `postgres` user, password from
  `postgresPassword`). Postgres host/port come from env (`POSTGRES_HOST`,
  `POSTGRES_PORT`); user/password from env/secrets.
- Configured backend services and their tables come from env/config (e.g.
  `BACKEND_SERVICES=order-service,inventory-service` plus a service→table map), matching
  `.Values.backends`.
- UI: separate React (Vite) SPA deployed as its own pod with Ingress, calling the
  provisioner REST API. Provisioner exposes CORS for the frontend ingress host (from
  values). Frontend image built from `frontend/` and deployed via Helm with its own
  Deployment/Service/Ingress.

Registry schema (in `postgres` DB, schema `registry`):
```sql
CREATE SCHEMA IF NOT EXISTS registry;
CREATE TABLE IF NOT EXISTS registry.customers (id TEXT PK, name TEXT, db_name TEXT, created_at TIMESTAMPTZ);
CREATE TABLE IF NOT EXISTS registry.tenants (id TEXT PK, customer_id TEXT FK, schema_name TEXT, created_at TIMESTAMPTZ);
-- tenant id is globally unique (PK), so a tenant name cannot be reused across customers.
```
The provisioner creates these idempotently on startup. Demo tables (e.g. `orders`,
`inventory`) use a fixed minimal schema: `id UUID PRIMARY KEY, name TEXT NOT NULL,
created_at TIMESTAMPTZ NOT NULL DEFAULT now()`. DDL is built with double-quoted
identifiers from validated/sanitized inputs.

Credentials are derived by the backend from the tenant id and service name, matching
the roles created by the provisioner. No external secret store is used in the demo.

## 9. Helm chart design

New directory: `/home/paly/projects/pgbouncer-pooling-demo/` (or reuse a new project).
Chart: `helm/` with `Chart.yaml`:

```yaml
apiVersion: v2
name: pgbouncer-pooling-demo
version: 0.1.0
appVersion: "0.1.0"
dependencies:
  - name: postgresql
    version: "15.5.38"          # pins PG 16.4.0 (bitnami chart 15.5.x ships PG16)
    repository: https://charts.bitnami.com/bitnami
    condition: postgresql.enabled
# NOTE: there is NO bitnami/pgbouncer Helm chart. PgBouncer ships as a custom
# Deployment in templates/ using bitnamilegacy/pgbouncer:1.24.1-debian-12-r10.
```

`values.yaml` (key parts):

```yaml
global:
  imagePullPolicy: IfNotPresent
  pgbouncerAuthPassword: "demo-pgbouncer-auth-pw"   # used by postgres init script + pgbouncer userlist

postgresql:
  enabled: true
  auth:
    postgresPassword: "demo-postgres-pw"   # provisioner connects as the `postgres` superuser
    database: "postgres"
  primary:
    initdb:
      scripts:
        01-pgbouncer-auth.sql: |
          CREATE ROLE pgbouncer_auth WITH LOGIN PASSWORD 'demo-pgbouncer-auth-pw' NOINHERIT;
          GRANT CONNECT ON DATABASE postgres TO pgbouncer_auth;
          CREATE OR REPLACE FUNCTION public.pgbouncer_get_auth(p_usename TEXT)
          RETURNS TABLE(usename name, passwd text) LANGUAGE sql SECURITY DEFINER
          SET search_path = pg_catalog AS $$
            SELECT usename, passwd::text FROM pg_shadow WHERE usename = p_usename;
          $$;
          REVOKE ALL ON FUNCTION public.pgbouncer_get_auth(TEXT) FROM PUBLIC;
          GRANT EXECUTE ON FUNCTION public.pgbouncer_get_auth(TEXT) TO pgbouncer_auth;
    persistence:
      enabled: true
      size: 8Gi

pgbouncer:
  enabled: true
  image: "bitnamilegacy/pgbouncer:1.24.1-debian-12-r10"
  adminPassword: "demo-pgbouncer-admin-pw"     # for pgbouncer_admin (admin console)
  exporterPassword: "demo-pgbouncer-exporter-pw" # for pgbouncer_exporter (stats)
  # The INI (spec §6) lives in a ConfigMap template; userlist.txt in a Secret with
  # double-quoted entries. No inline `configuration` value (no bitnami chart).

backends:
  - name: order-service
    replicas: 3
    table: orders
  - name: inventory-service
    replicas: 2
    table: inventory

backendImage:
  repository: backend-dummy
  tag: latest

provisioner:
  replicas: 1
  image:
    repository: provisioner
    tag: latest

frontend:
  replicas: 1
  image:
    repository: frontend
    tag: latest
  ingress:
    enabled: true
    className: nginx
    host: demo.local

# Observability scrape wiring: optional ServiceMonitors gated by prometheus.serviceMonitor.enabled
# (default false); otherwise handled externally via annotations/labels.
```

Templates:
- `backend-deployment.yaml` (loop over `.Values.backends`), Service per backend; includes
  `livenessProbe`/`readinessProbe` hitting `/actuator/health`, resource requests/limits,
  and env wiring (`SERVICE_NAME`, `TABLE_NAME`, `PGBouncer_HOST` (full service DNS
  `{{ .Release.Name }}-pgbouncer`), `PGBouncer_PORT`).
- `provisioner-deployment.yaml` + Service + Ingress (probes + resources), with env wiring
  (`POSTGRES_HOST={{ .Release.Name }}-postgresql`, `POSTGRES_PORT`, `POSTGRES_USER=postgres`,
  `POSTGRES_PASSWORD` from secret, `BACKEND_SERVICES` + service→table map from
  `.Values.backends`).
- `frontend-deployment.yaml` + Service + Ingress (React SPA). Frontend reads the
  provisioner base URL from a runtime ConfigMap (not build-time `VITE_API_BASE_URL`).
- `backend-ingress.yaml` (optional, path/host based) for HTTP access to backends during
  the demo; alternatively document `kubectl port-forward`.
- `pgbouncer-deployment.yaml`, `pgbouncer-service.yaml`, `pgbouncer-configmap.yaml`
  (pgbouncer.ini, spec §6), `pgbouncer-userlist-secret.yaml`.
- `_helpers.tpl`.

Note: the `pgbouncer_auth` password in the Postgres init script and the corresponding
entry in the pgbouncer `userlist.txt` Secret must agree; use a single Helm value
(`pgbouncerAuthPassword`, default `demo-pgbouncer-auth-pw`) rendered into both. ServiceMonitor
templates are optional and gated by `prometheus.serviceMonitor.enabled` (default false) so
scrape wiring can be handled externally or in-chart.

Image build: Dockerfiles for `backend-dummy` and `provisioner`; README documents
`docker build` + load into cluster (kind) or push to registry. For demo on existing
cluster, provide `skaffold.yaml` or `Makefile` (optional).

## 10. Observability (conceptual; not deployed by the chart)

Note: metrics are **metered in-app** (Hikari via Micrometer binder; PgBouncer via
exporter) regardless of scrape wiring, so they can be toggled on by simply enabling
ServiceMonitors/scrape annotations in environments with an OTel/Prometheus controller.
No observability infra is shipped by the chart.

- PgBouncer: `pgbouncer_exporter` (prometheuscommunity) scraping admin console on
  `:6432`? Actually admin console is on same port with special db `pgbouncer`; exporter
  connects as `stats_users` and runs `SHOW STATS/POOLS/DATABASES`. Deploy as separate
  Deployment with env `PGBOUNCER_DSN=postgres://pgbouncer_exporter:pw@pgbouncer:6432/pgbouncer`.
  Expose `/metrics` on :9127.
- Spring Boot: Actuator + Micrometer Prometheus registry; Hikari metrics
  (`hikaricp_connections_active`, `..._idle`, `..._pending`, `..._timeout_total`, etc.)
  auto-published when `io.micrometer:micrometer-core` on classpath.
- ServiceMonitors (Prometheus operator) for each backend Service (port http, path
  `/actuator/prometheus`) and the exporter — provided as optional templates gated by
  `prometheus.serviceMonitor.enabled` (default false) to match "reuse existing Prometheus".
- Grafana: provide a dashboard JSON ConfigMap (optional) showing pool sizes per tenant,
  wait times, PgBouncer client/server connections.
- Since OTel infra already exists in cluster, only scrape wiring + dashboards are in
  chart; no Prometheus/Grafana deployment.

## 11. Security

- Postgres superuser password via K8s Secret (chart generates or references existing).
  For demo, chart generates; document rotation. Provisioner uses the `postgres`
  superuser; no separate provisioner DB user.
- Backend credentials are derived locally (username `app_<tenant>_<service>`, password
  = tenant id), matching the roles provisioned by the provisioner. Passwords are
  simplified for the demo (password = tenant id). Backends hold no Postgres superuser
  credentials.
- PgBouncer admin port restricted to admin users; exporter uses stats user.
- NetworkPolicies (optional): only backends → pgbouncer, provisioner → postgres,
  exporter → pgbouncer.

## 12. Demo validation / success criteria

1. `helm install demo ./helm` succeeds; all pods ready.
2. Provisioner UI creates customer `acme`, tenant `t1`; DB `cust_acme`, schema
   `tenant_t1`, role `app_t1_orderservice` (sanitized per §5).
3. Backend `order-service` pod handles `GET /api/v1/whoami` with headers for acme/t1 and
   returns `{db: cust_acme, user: app_t1_orderservice, schema: tenant_t1}`.
4. Running 50 concurrent requests (k6, 10 VUs) shows PgBouncer `SHOW POOLS` for
   `(cust_acme, app_t1_orderservice)` with `sv_idle+sv_active ≤ 5`, while client Hikari
   pools show ≤ 2.
5. Creating a second tenant doubles PgBouncer pools but each stays ≤ 5; total backend
   connections bounded.
6. Grafana/Prometheus shows Hikari + PgBouncer metrics.

## 13. Risks & mitigations

- **Transaction pooling breaks session state**: mitigated by role-default `search_path`,
  `DISCARD ALL` reset, disabling prepared statement cache, avoiding advisory locks /
  LISTEN / temp tables in backends. Document limitations.
- **auth_query on pg_shadow not grantable (PG 14+)**: use security-definer function
  (§6). Spike to verify on Bitnami Postgres 16/17.
- **Dynamic DBs unknown to PgBouncer**: wildcard `*` entry handles any dbname; verify
  Bitnami chart passes through. Spike.
- **Unbounded pools per tenant**: not evicted in the demo; pools are small (max 2) and
  bounded by JVM memory. Acceptable for demo scale; production would add eviction.
- **Hikari `maxLifetime` vs. PgBouncer `server_lifetime`**: keep Hikari `maxLifetime`
  (30 min) below PgBouncer `server_lifetime` (1 h) as a hygiene measure so client pools
  recycle their (PgBouncer-facing) connections periodically; PgBouncer manages its own
  server-connection lifecycle transparently to clients.
- **Connection churn**: mitigated by `minIdle 1` keepalive per tenant pool (configurable).
- **Postgres `max_connections`**: many lazy pools can still exhaust it under concurrent
  load; raise it in the Bitnami values or cap via PgBouncer `max_db_connections` if
  needed. Demo scale is fine.

## 14. Open questions / spikes

1. (Resolved) No `bitnami/pgbouncer` chart → ship PgBouncer as a custom Deployment
   (ConfigMap + Secret). See `docs/spike-findings.md`.
2. (Resolved) `pgbouncer_get_auth` security-definer works on PG 17.6 (expected on 16.4);
   `pg_shadow` superuser-only. See `docs/spike-findings.md`.
3. (Resolved) Wildcard DB + `auth_query` works end-to-end with dynamic roles/DBs.
   See `docs/spike-findings.md`.
4. (Resolved) Per-tenant × per-microservice users; password = tenant id; minIdle = 1.
5. (Resolved) Spring Boot 4.1.2 target; credentials derived locally.
6. (Resolved) Postgres chart for PG16: pin `bitnami/postgresql` to chart 15.5.38.
7. (Resolved) PgBouncer image: `bitnamilegacy/pgbouncer:1.24.1-debian-12-r10`.
8. (Resolved) Frontend uses runtime ConfigMap for provisioner base URL.
9. (Resolved) Pool eviction skipped for demo; cleanup endpoints included.

## 15. Implementation phases

Phase 0 — Spikes (1–2 days): verify PgBouncer wildcard/auth_query on Bitnami charts;
PoC connection. Write findings back to this spec.

Phase 1 — Backend dummy (2–3 days): Spring Boot service with TenantContext, derived
creds, routing Hikari→PgBouncer, Actuator metrics, demo CRUD endpoints, tests.

Phase 2 — Provisioner (2–3 days): customer/tenant CRUD, DB/schema/role creation,
per-service tables + ownership, REST API for React UI, registry.

Phase 3 — Helm chart (2–3 days): chart skeleton, dependencies, templates, values,
ServiceMonitors, exporter, docs.

Phase 4 — Integration & demo polish (1–2 days): seed script, Grafana dashboard, README
walkthrough, load test showing bounded connections.

Phase 5 — Hardening (optional): NetworkPolicies, cleanup endpoints.

## 16. Directory layout (proposed)

```
pgbouncer-pooling-demo/
├── pom.xml                      # parent (multi-module)
├── backend-dummy/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/...
├── provisioner/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/...
├── frontend/                 # React (Vite) SPA
│   ├── package.json
│   ├── Dockerfile
│   └── src/...
├── helm/
│   ├── Chart.yaml
│   ├── values.yaml
│   └── templates/
├── load-test/                   # k6 or wrk scripts
├── README.md
└── Makefile
```

---

## 17. Spike findings (Phase 0)

Verified via local Docker PoC (K8s cluster unreachable during spike). Full details:
`docs/spike-findings.md`. Key results:

- No `bitnami/pgbouncer` Helm chart exists → ship PgBouncer as a custom Deployment
  using `bitnamilegacy/pgbouncer:1.24.1-debian-12-r10`, mounting a ConfigMap
  (`pgbouncer.ini`, spec §6) + Secret (`userlist.txt`, double-quoted entries), running
  the binary directly (entrypoint override).
- `pgbouncer_get_auth` security-definer function works on PG 17.6 (expected on 16.4);
  `pg_shadow` is superuser-only. Roles are cluster-global: create once in init script,
  provisioner only GRANTs in customer DBs.
- Wildcard `*` database + `auth_query` works end-to-end: client-supplied `dbname`
  flows through to Postgres; `SHOW POOLS` shows one transaction pool per
  `(db, user)`.
- `ignore_startup_parameters = extra_float_digits,client_encoding` sufficient for JDBC.
- Postgres chart for PG16: `bitnami/postgresql` 15.5.38 (appVersion 16.4.0).

*End of spec. Implementation requires switching out of Plan mode.*