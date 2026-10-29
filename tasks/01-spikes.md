# Task 01 — Phase 0: Spikes / PoC (COMPLETED with smart defaults)

> Status: **Completed via local Docker PoC** (K8s cluster unreachable during spike;
> mechanism validated locally). Detailed findings: `docs/spike-findings.md`.

## What was verified

1. **Postgres security-definer auth function** — works on PG 17.6 (mechanism identical
   on PG 16). `pgbouncer_auth` can execute `pgbouncer_get_auth(...)` and receives the
   SCRAM-SHA-256 hash. See `docs/spike-findings.md` Spike 2.
2. **End-to-end wildcard DB + auth_query** — connection via PgBouncer to `cust_acme` as
   `app_t1_orderservice` returns correct db/user/schema; `SHOW POOLS` shows one
   transaction pool per `(db, user)`. See Spike 3.
3. **ignore_startup_parameters** — `extra_float_digits,client_encoding` sufficient.

## Key adjustments driven by findings (apply to later tasks)

- **No `bitnami/pgbouncer` Helm chart exists.** Task 05 must ship PgBouncer as a
  custom Deployment (ConfigMap + Secret, entrypoint override). See `docs/spike-findings.md`.
- **Postgres chart for PG16:** pin `bitnami/postgresql` to chart `15.5.38`
  (appVersion 16.4.0), not latest (18.x).
- **Images:** use `bitnamilegacy/*` tags tested locally for reproducibility.
- **Userlist quoting:** all entries double-quoted.
- **admin_users:** explicitly include `pgbouncer_admin` in INI.

## Remaining unverified (deferred)

- Bitnami postgresql chart initdb script rendering for 15.5.x values paths.
- ServiceMonitor CRDs in target cluster.
- Actual K8s rollout (cluster unreachable during spike).
- JDBC prepared-statement behavior under load (mitigation in place).

## Acceptance

Findings documented in `docs/spike-findings.md`; plan adjusted. Task 01 complete.
