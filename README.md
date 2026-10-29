# pg-db-pooling — multi-tenant Postgres pooling demo

Demo of replacing per-tenant Hikari pools with external PgBouncer pooling while
keeping per-customer databases and per-tenant schemas/users.

## Prerequisites

- Kubernetes cluster (kind, minikube, or remote) with `kubectl` configured.
- Helm 3.
- Docker.
- `psql` (for `SHOW POOLS`).
- `k6` (for the load test) — optional but recommended.
- An ingress controller is optional; the chart uses `frontend.demo.local` for the
  frontend, `provisioner.demo.local` for the provisioner, and
  `backend.demo.local/<service>` (e.g. `backend.demo.local/order-service`) for the
  backends when ingress is enabled.

## Build images

```bash
make build-images
```

## Load images into the cluster

kind:

```bash
make load-images
```

For a registry instead, tag/push the images and override chart values, e.g.

```bash
helm upgrade --install demo ./helm -n default --create-namespace \
  --set backendImage.repository=registry.example.com/backend-dummy \
  --set backendImage.tag=0.1.0 \
  --set provisioner.image.repository=registry.example.com/provisioner \
  --set provisioner.image.tag=0.1.0 \
  --set frontend.image.repository=registry.example.com/frontend \
  --set frontend.image.tag=0.1.0
```

## Install the chart

```bash
make helm-install
# or: helm upgrade --install demo ./helm -n default --create-namespace
```

Wait for all pods to become ready:

```bash
kubectl get pods -n default -w
```

## Port-forward services

In separate terminals (or use an ingress):

```bash
kubectl port-forward -n default svc/demo-frontend         8080:80
kubectl port-forward -n default svc/demo-provisioner      8081:80
kubectl port-forward -n default svc/demo-order-service    8082:80
kubectl port-forward -n default svc/demo-pgbouncer        6432:6432
```

## Create a customer and tenant

Via curl (admin token defaults to `demo-admin-token`):

```bash
curl -sS -X POST http://localhost:8081/api/customers \
  -H 'Content-Type: application/json' \
  -H 'X-Admin-Token: demo-admin-token' \
  -d '{"name":"acme"}'

curl -sS -X POST http://localhost:8081/api/customers/acme/tenants \
  -H 'Content-Type: application/json' \
  -H 'X-Admin-Token: demo-admin-token' \
  -d '{"name":"t1"}'
```

Or seed in bulk:

```bash
CUSTOMERS=3 TENANTS=2 make seed
# calls POST /api/seed
```

## Run whoami and CRUD

```bash
curl -sS http://localhost:8082/api/v1/whoami \
  -H 'X-Customer-Id: acme' -H 'X-Tenant-Id: t1'

curl -sS http://localhost:8082/api/v1/items \
  -H 'X-Customer-Id: acme' -H 'X-Tenant-Id: t1'

curl -sS -X POST http://localhost:8082/api/v1/items \
  -H 'Content-Type: application/json' \
  -H 'X-Customer-Id: acme' -H 'X-Tenant-Id: t1' \
  -d '{"name":"foo"}'
```

Expected `whoami` response:

```json
{"service":"order-service","customer":"acme","tenant":"t1","db":"cust_acme","user":"app_t1_orderservice","schema":"tenant_t1"}
```

## Run the load test

With the port-forwards above running:

```bash
make load-test
```

The script runs k6 (10 VUs) against the backend and captures `SHOW POOLS`
before/after into `before-pools.txt` / `after-pools.txt`, then asserts that
`sv_idle + sv_active <= 5` for the `(cust_acme, app_t1_orderservice)` pool.

Override connection target via env vars, e.g. `CUSTOMER=seedc1 TENANT=seedt1_1`.

## Inspect PgBouncer pools manually

```bash
PGPASSWORD=demo-pgbouncer-admin-pw \
  psql -h localhost -p 6432 -U pgbouncer_admin -d pgbouncer -c 'SHOW POOLS;'
```

## View metrics

Backend metrics (Prometheus format) are exposed at `/actuator/prometheus`:

```bash
curl -sS http://localhost:8082/actuator/prometheus | head
```

The PgBouncer exporter is scraped on port 9127. To enable in-cluster scraping,
set `prometheus.serviceMonitor.enabled=true` (requires ServiceMonitor CRDs):

```bash
helm upgrade demo ./helm -n default --set prometheus.serviceMonitor.enabled=true
```

Import `docs/grafana-dashboard.json` into Grafana for a starter dashboard
(Hikari + PgBouncer panels).

## Hardening notes

- **Pool eviction (production):** PgBouncer reuses server connections; tune
  `server_lifetime` / `server_idle_timeout` and application-side pool sizing.
  The demo keeps a single small pool per backend; production should size Hikari
  pools to the number of backend pods, not tenants.
- **Postgres `max_connections`:** Bitnami defaults may be low for many tenants.
  Raise via `postgresql.primary.extendedConfiguration` / `max_connections` if
  needed (e.g. 200+). PgBouncer's `max_db_connections` should be tuned per
  database to bound backend connections (see `pgbouncer-configmap`).
- **Secret rotation:** rotate `pgbouncerAuthPassword`, `adminPassword`,
  `exporterPassword`, `POSTGRES_PASSWORD`, and `ADMIN_TOKEN` by updating Helm
  values/secrets and restarting pods; re-run the provisioner init SQL for the
  `pgbouncer_auth` role password. Tenant role passwords are derived from tenant
  ids and should be rotated by re-provisioning tenants.
- **Cleanup:** destructive endpoints require `X-Admin-Token` and `?confirm=true`:
  ```bash
  curl -sS -X DELETE 'http://localhost:8081/api/customers/acme/tenants/t1?confirm=true' \
    -H 'X-Admin-Token: demo-admin-token'
  curl -sS -X DELETE 'http://localhost:8081/api/customers/acme?confirm=true' \
    -H 'X-Admin-Token: demo-admin-token'
  ```

## Uninstall

```bash
make helm-uninstall
```

## Validation

See `docs/validation.md` for the success-criteria checklist from the overview.
