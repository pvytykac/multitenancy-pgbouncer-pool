# Task 00 — Overview, architecture, and shared conventions

> Source spec: `db-multitenant-pooling.md`. Read this file first; every other task
> file assumes it. Each later task is self-contained for its scope but references
> the conventions defined here.

## Purpose

Replace per-tenant/per-schema Hikari pools in Spring Boot microservices with external
PgBouncer pooling, while preserving per-customer DB + per-tenant schema isolation
and per-tenant DB users. Ship a runnable demo on Kubernetes (Helm).

Status: **Draft spec, nothing implemented.** Implementation requires leaving Plan mode.

## Goals

- Reduce connection count from `microservices × replicas × tenants × schemas` to a
  bounded `0–5 connections per (customer DB, tenant user)` maintained by PgBouncer.
- Microservices keep many tiny keepalive Hikari pools pointed at PgBouncer (one per
  `(customer DB, tenant user)`). Target DB selected per connection over the wire via
  JDBC `dbname`; PgBouncer's wildcard `*` database entry passes client-supplied
  `dbname` through to Postgres; PgBouncer collapses pools with the same `(dbname, user)`
  across JVMs/replicas into one server pool.
- Tenant schema isolation via DB users scoped to their schema
  (`ALTER ROLE ... SET search_path`), so PgBouncer transaction pooling stays safe.
- Tenant credentials deterministic: username `app_<tenant>_<service>`, password = tenant id
  (simplified for demo; not production-grade).
- Demo: Helm chart deploying Postgres (Bitnami), PgBouncer (Bitnami), `x` dummy Spring
  Boot backends with `n` replicas each, a provisioner service + simple UI, observability
  wiring (PgBouncer exporter + Spring Boot Actuator/Prometheus).
- Reuse cluster's existing OTel/Prometheus/Grafana; do not deploy observability infra in
  chart (only ServiceMonitors / scrape annotations / dashboards).

## Non-goals

- Production HA, multi-region, backups/PITR.
- Application-level multi-tenancy beyond DB/schema isolation.
- Migrating existing production microservices (demo only).

## Connection math

Current (per JVM): one Hikari pool per `(customer DB × tenant schema)`, `minIdle ≥ 1`,
`maxPoolSize ≥ 10`. Cost `10 services × 3 replicas × 200 customers × tenants × schemas`
≈ tens of thousands of idle connections.

Desired:
- Client (Hikari): `minIdle 1` (keepalive), `maxPoolSize 2` per `(DB, user)`, pointed at PgBouncer.
- PgBouncer: `default_pool_size = 5`, `min_pool_size = 0`, `reserve_pool_size = 0`,
  `pool_mode = transaction`. One backend pool per `(actual dbname, user)` → `0–5`
  connections per tenant.

## Architecture

```
┌──────────────┐  HTTP   ┌─────────────────┐
│   Demo UI    │────────►│  Provisioner    │
│  (React SPA) │         │  (Spring Boot)  │
└──────────────┘         └────┬────┬───────┘
                              │    │ creates DBs/schemas/roles
                              │    │ creds derived locally
                              ▼    ▼
┌─────────────────────────────────────────────────────────────┐
│                       Kubernetes                            │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐             │
│  │ backend ×N │  │ backend ×N │  │ backend ×N │  (x services)│
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘             │
│        └───────────────┼───────────────┘                     │
│                        ▼                                     │
│                 ┌──────────────┐                              │
│                 │  PgBouncer   │  (bitnami/pgbouncer) :6432   │
│                 └──────┬───────┘                              │
│                        ▼                                     │
│                 ┌──────────────┐                              │
│                 │  Postgres    │  (bitnami/postgresql) :5432  │
│                 └──────────────┘                              │
└─────────────────────────────────────────────────────────────┘
```

Data flow (request to a backend for tenant T of customer C):
1. Ingress → backend pod; request carries `X-Customer-Id`, `X-Tenant-Id` (or path).
2. Backend sets `TenantContext` (ThreadLocal).
3. Backend derives tenant credentials locally: username `app_<tenant>_<service>`,
   password = tenant id (simplified).
4. Backend obtains/creates a small Hikari pool keyed by `(customer, tenant, service)` →
   `(dbName=cust_<c>, user)` pointing at `pgbouncer:6432`, borrows a connection, runs
   query, returns connection.
5. PgBouncer routes the client connection to backend Postgres using the `dbname` from
   the startup packet (wildcard `*` database entry), authenticates the user via
   `auth_query`, and reuses/creates a server connection in that `(db, user)` pool.

## Data model

- **Customer** → Postgres database `cust_<customerId>` (lowercased, sanitized). Customer
  IDs globally unique because DB names are cluster-global.
- **Tenant** → schema `tenant_<tenantId>` inside the customer DB. Tenant IDs are
  **globally unique** (enforced by `registry.tenants.id` PK) because Postgres roles are
  cluster-global.
- **User** → Postgres role `app_<tenantId>_<service>` (one per tenant × microservice):
  - `LOGIN PASSWORD '<tenantId>'`
  - `ALTER ROLE ... SET search_path TO tenant_<tenantId>, public`
  - `GRANT USAGE, CREATE ON SCHEMA tenant_<tenantId> TO ...`
  - ownership of that service's demo table(s) in the tenant schema
  - `GRANT SELECT/INSERT/... ON ALL TABLES IN SCHEMA ...` + `ALTER DEFAULT PRIVILEGES`
    for future tables.
- **PgBouncer auth user** → role `pgbouncer_auth` with `CONNECT` on all DBs and
  `EXECUTE` on `pgbouncer_get_auth(...)`, used by PgBouncer `auth_query`. Created in
  `postgres` DB by init script and recreated by provisioner in every customer DB.
- **Provisioner admin** → Postgres superuser (bitnami `postgres` user) for DDL; stored
  in K8s secret, mounted into provisioner.

## Naming constraints

Sanitize identifiers to `[a-z0-9_]+`. Sanitization lowercases the value and strips
hyphens (and any other non-`[a-z0-9]` characters); fixed tokens (`app`, tenant, service)
joined with `_` as delimiter (e.g. `order-service` → `orderservice`, role
`app_t1_orderservice`). Enforce Postgres 63-char limit for database, schema, and role
names by **rejecting** over-long inputs (not truncating). Limit checked against the
*derived* names (`cust_<c>`, `tenant_<t>`, `app_<t>_<service>`), not raw inputs.
Customer and tenant IDs validated to `[a-z0-9_]+` on input (already canonical,
lowercase), so raw and sanitized forms match; derived password (tenant id) matches role
password exactly. Service names are the exception: may contain hyphens/other chars and are
sanitized before use in identifiers and credential derivation. Sanitization duplicated in
backend and provisioner (no shared module).

## PgBouncer configuration (summary)

Bitnami chart, `configuration` override (see spec §6 for full INI). Key values:
`pool_mode = transaction`, `default_pool_size = 5`, `min_pool_size = 0`,
`reserve_pool_size = 0`, wildcard `*` database entry, `auth_query`, `auth_user = pgbouncer_auth`,
`server_reset_query = DISCARD ALL`, `ignore_startup_parameters = extra_float_digits,client_encoding`.
Postgres side: security-definer function `public.pgbouncer_get_auth(p_usename TEXT)` over
`pg_shadow`, owned by superuser. Must exist in every DB (including each customer DB).

Backend JDBC must disable prepared statements:
`prepareThreshold=0&preparedStatementCacheQueries=0`.

Transaction-pooling limitations (document for backend authors): no session-level state,
no advisory locks, no `LISTEN/NOTIFY`, no temp tables, no `SET` outside transaction.

## Security (summary)

- Postgres superuser password via K8s Secret (chart generates for demo). Provisioner uses
  `postgres` superuser; no separate provisioner DB user.
- Backend credentials derived locally (`app_<tenant>_<service>`, password = tenant id).
  Backends hold no superuser credentials.
- PgBouncer admin port restricted to admin users; exporter uses stats user.
- NetworkPolicies optional.

## Success criteria

1. `helm install demo ./helm` succeeds; all pods ready.
2. Provisioner UI creates customer `acme`, tenant `t1`; DB `cust_acme`, schema
   `tenant_t1`, role `app_t1_orderservice`.
3. Backend `order-service` handles `GET /api/v1/whoami` with headers for acme/t1 and
   returns `{db: cust_acme, user: app_t1_orderservice, schema: tenant_t1}`.
4. 50 concurrent requests (k6, 10 VUs) shows PgBouncer `SHOW POOLS` for
   `(cust_acme, app_t1_orderservice)` with `sv_idle+sv_active ≤ 5`, while client Hikari
   pools show ≤ 2.
5. Creating a second tenant doubles PgBouncer pools but each stays ≤ 5; total backend
   connections bounded.
6. Grafana/Prometheus shows Hikari + PgBouncer metrics.

## Directory layout (proposed)

```
pg-db-pooling/
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

## Implementation phases (→ task files)

- Phase 0 — Spikes → `01-spikes.md`
- Phase 1 — Backend dummy → `02-backend-dummy.md` (also creates parent pom + module)
- Phase 2 — Provisioner → `03-provisioner.md`
- Frontend (React) → `04-frontend.md`
- Phase 3 — Helm chart → `05-helm-chart.md`
- Phase 4 — Integration & demo polish → `06-integration-polish.md`
- Phase 5 — Hardening (optional) → `07-hardening.md`

## Open questions / spikes

1. (Resolved) No `bitnami/pgbouncer` Helm chart exists → ship PgBouncer as a custom
   Deployment. See `docs/spike-findings.md`.
2. (Resolved) `pgbouncer_get_auth` security-definer works on PG 17.6 (expected on 16.4);
   `pg_shadow` is superuser-only. See `docs/spike-findings.md`.
3. (Resolved) Wildcard DB + `auth_query` works end-to-end with dynamic roles/DBs.
   See `docs/spike-findings.md`.
4. (Resolved) Per-tenant × per-microservice users; password = tenant id; minIdle = 1.
5. (Resolved) Spring Boot 4.1.2 target (verify availability; repo currently uses
   4.0.0-RC1 — fall back to latest 4.0.x if needed); credentials derived locally.
6. (Resolved) Postgres chart for PG16: pin `bitnami/postgresql` to chart 15.5.38.

## Risks (summary)

- Transaction pooling breaks session state → role-default search_path, DISCARD ALL,
  disabled prepared-statement cache, avoid advisory locks/LISTEN/temp tables/SET.
- auth_query on pg_shadow not grantable (PG 14+) → security-definer function.
- Dynamic DBs unknown to PgBouncer → wildcard `*` entry.
- Unbounded pools per tenant → not evicted in demo; small (max 2), bounded by JVM
  memory.
- Hikari maxLifetime (30 min) < PgBouncer server_lifetime (1 h).
- Postgres max_connections → raise in Bitnami values or cap via max_db_connections.
