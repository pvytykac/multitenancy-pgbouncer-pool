# Task 05 — Phase 3: Helm chart (`helm/`)

> Prerequisites: `00-overview`, spike findings (task 01), Docker images from tasks
> 02–04. Timebox 2–3 days.

## Objective

Create `helm/` chart deploying Postgres (Bitnami), PgBouncer (Bitnami), `x` backend
dummy services (n replicas each), provisioner, frontend, PgBouncer exporter, optional
ServiceMonitors, and wiring. No observability infra deployed (reuse existing).

## Directory

```
helm/
├── Chart.yaml
├── values.yaml
├── templates/
│   ├── _helpers.tpl
│   ├── backend-deployment.yaml
│   ├── backend-service.yaml
│   ├── backend-ingress.yaml
│   ├── provisioner-deployment.yaml
│   ├── provisioner-service.yaml
│   ├── provisioner-ingress.yaml
│   ├── frontend-deployment.yaml
│   ├── frontend-service.yaml
│   ├── frontend-ingress.yaml
│   ├── pgbouncer-deployment.yaml        # custom Deployment (no chart)
│   ├── pgbouncer-service.yaml
│   ├── pgbouncer-configmap.yaml         # pgbouncer.ini (spec §6)
│   ├── pgbouncer-userlist-secret.yaml   # userlist.txt (quoted entries)
│   ├── pgbouncer-exporter-deployment.yaml
│   ├── pgbouncer-exporter-service.yaml
│   ├── servicemonitor-backend.yaml
│   ├── servicemonitor-exporter.yaml
│   └── grafana-dashboard-configmap.yaml (optional)
```

## Chart.yaml

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
# NOTE: no bitnami/pgbouncer chart exists. PgBouncer ships as a custom Deployment
# (templates/pgbouncer-*.yaml) using bitnamilegacy/pgbouncer image.
```

Pin versions per spike findings.

## values.yaml (key parts)

Use spec §9 values as starting point. Key sections:

- `global.imagePullPolicy`
- `postgresql`: enabled, auth.postgresPassword, database, primary.initdb.scripts
  (01-pgbouncer-auth.sql), persistence.
- `pgbouncer`: enabled, image (bitnamilegacy/pgbouncer:...), adminPassword,
  exporterPassword. The INI lives in a ConfigMap template (spec §6); userlist in a Secret
  (quoted entries). No `configuration` value from a chart.
- `backends`: list of `{name, replicas, table}`.
- `backendImage`: repository/tag.
- `provisioner`: replicas, image, env.
- `frontend`: replicas, image, ingress.
- `pgbouncerAuthPassword` single value used in both Postgres init script and pgbouncer
  userlist secret.
- `prometheus.serviceMonitor.enabled` (default false).
- resource requests/limits, probes.

## Templates requirements

### Postgres init script (values → postgresql.primary.initdb.scripts)

```sql
CREATE ROLE pgbouncer_auth WITH LOGIN PASSWORD '<pgbouncerAuthPassword>' NOINHERIT;
GRANT CONNECT ON DATABASE postgres TO pgbouncer_auth;
CREATE OR REPLACE FUNCTION public.pgbouncer_get_auth(p_usename TEXT)
RETURNS TABLE(usename name, passwd text) LANGUAGE sql SECURITY DEFINER
SET search_path = pg_catalog AS $$
  SELECT usename, passwd::text FROM pg_shadow WHERE usename = p_usename;
$$;
REVOKE ALL ON FUNCTION public.pgbouncer_get_auth(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.pgbouncer_get_auth(TEXT) TO pgbouncer_auth;
```

### PgBouncer (custom Deployment — no chart)

Ship PgBouncer as a custom Deployment in `templates/pgbouncer-*.yaml`:
- Image: `bitnamilegacy/pgbouncer:1.24.1-debian-12-r10` (override if needed).
- Mount ConfigMap `pgbouncer-configmap` at `/etc/pgbouncer/pgbouncer.ini` (full INI from
  spec §6, including wildcard `*` database entry, auth_query, pool_mode, etc.).
- Mount Secret `pgbouncer-userlist` at `/etc/pgbouncer/userlist.txt` with **double-quoted**
  entries: `"pgbouncer_auth" "..."`, `"pgbouncer_admin" "..."`, `"pgbouncer_exporter" "..."`.
- Override container command to run
  `/opt/bitnami/pgbouncer/bin/pgbouncer /etc/pgbouncer/pgbouncer.ini` (bypass bitnami
  entrypoint, which regenerates config). Ensure pidfile/logfile paths are writable by
  uid 1001 (defaults under `/opt/bitnami/pgbouncer/tmp` are fine).
- Service exposing 6432. admin_users = pgbouncer_admin (and optionally postgres).

### backend-deployment.yaml

Loop over `.Values.backends` (use range). Per backend:
- Deployment with `replicas`, image.
- Env: `SERVICE_NAME`, `TABLE_NAME`, `PGBouncer_HOST={{ .Release.Name }}-pgbouncer`,
  `PGBouncer_PORT=6432`.
- liveness/readiness probes: `/actuator/health`.
- resource requests/limits.
- Service per backend (port 8080 → 80? document).
- Optional Ingress.

### provisioner-deployment.yaml

- Env: `POSTGRES_HOST={{ .Release.Name }}-postgresql`, `POSTGRES_PORT=5432`,
  `POSTGRES_USER=postgres`, `POSTGRES_PASSWORD` from secret, `BACKEND_SERVICES` (comma
  list from values), service→table map, `ADMIN_TOKEN`, `CORS_ORIGINS`.
- probes, resources.
- Service + Ingress.

### frontend-deployment.yaml

- image; env `VITE_API_BASE_URL` if runtime config; else baked.
- Service + Ingress (host from values).

### pgbouncer-exporter

Separate Deployment scraping admin console:
`PGBOUNCER_DSN=postgres://pgbouncer_exporter:<pw>@{{ .Release.Name }}-pgbouncer:6432/pgbouncer`.
Expose `/metrics` on :9127. Service for scraping.

### ServiceMonitors (gated by `prometheus.serviceMonitor.enabled`, default false)

- backend services: port http, path `/actuator/prometheus`.
- exporter: port metrics, path `/metrics`.

### _helpers.tpl

Standard labels, names, selectors.

## NetworkPolicies (optional, task 07)

Leave for hardening; chart may include disabled by default.

## Acceptance criteria

- `helm lint ./helm` passes.
- `helm template demo ./helm` renders without errors.
- `helm install demo ./helm` (on cluster with images loaded) succeeds; all pods ready.
- Values override works (e.g. backend replicas).
- ServiceMonitors render only when enabled.

## Risks / notes

- Bitnami chart version pinning must match spike findings; wildcard config must pass
  through.
- `userlist.txt` password agreement: single value `pgbouncerAuthPassword`.
- Image pull: for kind, load images or set imagePullPolicy and registry.
- Provide README (task 06) with build/load steps.

## Optional: skaffold.yaml / Makefile

Document `docker build` + load into cluster (kind) or push. Makefile targets:
build-images, load-images, helm-install, helm-uninstall.
