# Validation against overview §12 success criteria

| # | Criterion | Status | Notes |
|---|-----------|--------|-------|
| 1 | `helm install demo ./helm` succeeds; all pods ready | Pending (cluster) | Validated via `helm lint` / `helm template` locally. |
| 2 | Provisioner creates customer `acme`, tenant `t1`; DB `cust_acme`, schema `tenant_t1`, role `app_t1_orderservice` | Pending (cluster) | Exercised by README curl steps. |
| 3 | Backend `order-service` handles `GET /api/v1/whoami` and returns `{db, user, schema}` | Pending (cluster) | Exercised by README curl steps. |
| 4 | 50 concurrent requests show PgBouncer `SHOW POOLS` `(cust_acme, app_t1_orderservice)` with `sv_idle+sv_active <= 5`, Hikari <= 2 | Pending (cluster) | `load-test/run.sh` asserts server side. |
| 5 | Second tenant doubles pools but each stays <= 5; backend connections bounded | Pending (cluster) | Create a second tenant and re-run load test. |
| 6 | Grafana/Prometheus shows Hikari + PgBouncer metrics | Pending (cluster) | Enable ServiceMonitors; import `docs/grafana-dashboard.json`. |

## Local checks performed

- `helm lint ./helm`
- `helm template demo ./helm`
- JSON validity of `docs/grafana-dashboard.json`
