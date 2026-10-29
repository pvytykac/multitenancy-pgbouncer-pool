# Spike findings (Phase 0)

Date: 2026-10-29. Environment: local Docker (no K8s cluster reachable; cluster DNS
unresolvable during spike). Postgres image: `bitnamilegacy/postgresql:17.6.0-debian-12-r4`
(PG 17.6). PgBouncer image: `bitnamilegacy/pgbouncer:1.24.1-debian-12-r10`
(PgBouncer 1.24.1). Chart repo: bitnami/postgresql 15.5.x ships PG 16.4.0;
no bitnami/pgbouncer chart exists.

## Spike 1 — Bitnami PgBouncer chart capabilities

- **Result:** There is **no `bitnami/pgbouncer` Helm chart** in the bitnami repo
  (`helm search repo bitnami | grep pgbouncer` → empty). The spec's chart dependency
  must be replaced with a custom Deployment using the bitnami/pgbouncer image.
- The bitnami pgbouncer **container** generates its config from env vars
  (`POSTGRESQL_HOST`, `PGBOUNCER_DATABASE`, `PGBOUNCER_AUTH_QUERY`, etc.) and supports a
  `PGBOUNCER_DATABASE='*'` value, which renders `*=host=... auth_user=...` (wildcard).
  However the generated `userlist.txt` was malformed (mixed quoting) and `admin_users`
  did not pick up the env value reliably.
- **Decision:** Mount a custom `pgbouncer.ini` ConfigMap + `userlist.txt` Secret and run
  the pgbouncer binary directly (override entrypoint), as validated below. This gives
  full control over the INI from spec §6.

## Spike 2 — Postgres security-definer auth function

- Validated on PG 17.6 (mechanism identical on PG 16).
- Init SQL works as written (spec §6). `pgbouncer_auth` can `SELECT` from
  `public.pgbouncer_get_auth('app_t1_orderservice')` and receives the SCRAM-SHA-256
  hash. `pg_shadow` is superuser-only; the SECURITY DEFINER function (owned by
  `postgres`) bridges it.
- **Gotcha:** role `pgbouncer_auth` must be created in `postgres` DB by init script
  AND recreated/granted in every customer DB (CONNECT + EXECUTE). Provisioner does this
  (task 03).
- **Gotcha:** `CREATE ROLE pgbouncer_auth` in a customer DB where it already exists
  globally fails; use `DO $$ BEGIN IF NOT EXISTS ...` or catch duplicate. Since roles
  are cluster-global, create once (init script) and only GRANT in customer DBs.

## Spike 3 — End-to-end wildcard + auth_query

- Created `cust_acme` DB, installed function + grants, created schema `tenant_t1`,
  role `app_t1_orderservice` (password `t1`, search_path default), table `orders`.
- Ran pgbouncer (custom config, wildcard `*`) on :6432.
- Connection succeeded:
  ```
  psql -h 127.0.0.1 -p 6432 -U app_t1_orderservice -d cust_acme
  current_database=cust_acme, current_user=app_t1_orderservice, current_schema=tenant_t1
  ```
- `SHOW POOLS` shows one pool per `(cust_acme, app_t1_orderservice)` with transaction
  pool_mode. Server connections = 1 idle after query.
- **ignore_startup_parameters:** `extra_float_digits,client_encoding` sufficient for
  JDBC; no additional params observed with psql.

## Spike 4 — JDBC prepared statements

- Not exercised via JDBC in this spike. Mechanism well-documented: PgBouncer
  transaction pooling breaks server-side prepared statements unless disabled.
- Mitigation stands: JDBC URL must include
  `prepareThreshold=0&preparedStatementCacheQueries=0`. `ApplicationName=<service>` is
  accepted.

## Adjustments to plan

- **Postgres chart version for PG16:** use `bitnami/postgresql` chart `15.5.38`
  (appVersion 16.4.0). Default repo latest (18.x) ships PG18; pin to 15.5.x.
- **PgBouncer:** no chart; ship a custom Deployment in `helm/templates/` using
  `bitnamilegacy/pgbouncer:1.24.1-debian-12-r10` image, mounting ConfigMap
  (`pgbouncer.ini`) + Secret (`userlist.txt`), overriding entrypoint to run
  `/opt/bitnami/pgbouncer/bin/pgbouncer /etc/pgbouncer/pgbouncer.ini`.
- **Image registry:** use `bitnamilegacy/*` images for reproducibility (bitnami secure
  images now require auth). Document migration to `bitnami/*` if available.
- **Userlist quoting:** all entries must be double-quoted `"user" "pass"`; mixed
  quoting breaks auth file.
- **admin_users:** include `pgbouncer_admin` (and optionally `postgres`) in the INI;
  do not rely on env default.

## Open items still unverified

- Bitnami postgresql chart initdb script rendering for 15.5.x (values path
  `primary.initdb.scripts`) — verify during Helm task.
- ServiceMonitor CRDs availability in target cluster.
- Actual K8s rollout (cluster unreachable during spike).
