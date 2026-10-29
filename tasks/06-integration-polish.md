# Task 06 — Phase 4: Integration & demo polish

> Prerequisites: tasks 02–05. Timebox 1–2 days.

## Objective

Wire everything together: seed script, Grafana dashboard, README walkthrough, load test
showing bounded connections, Makefile/skaffold. Validate success criteria from overview §12.

## Deliverables

### 1. Seed script / endpoint

- `POST /api/seed` already in provisioner (task 03). Document usage:
  create N customers × M tenants. Add a small shell script `load-test/seed.sh` or
  README curl examples.

### 2. Load test (`load-test/`)

- k6 (or wrk) script hitting a backend service's `/api/v1/items` or `/api/v1/whoami`
  with `X-Customer-Id`/`X-Tenant-Id` headers, 10 VUs, 50 concurrent requests.
- Collect PgBouncer `SHOW POOLS` output before/after; assert `sv_idle+sv_active ≤ 5`.
- Document how to run against port-forwarded services.

### 3. Grafana dashboard

- Provide a dashboard JSON (ConfigMap optional) showing:
  - Hikari connections active/idle/pending/timeout per pool (tenant).
  - PgBouncer client/server connections, pools per (db,user).
  - Wait times.
- Place under `helm/templates/grafana-dashboard-configmap.yaml` (optional, gated) or
  `docs/grafana-dashboard.json`.

### 4. README.md

Walkthrough:
- Prerequisites (cluster, helm, docker, kubectl).
- Build images (`make build-images`).
- Load into cluster (`make load-images` for kind) or push.
- `helm install demo ./helm`.
- Port-forward provisioner/frontend/backends.
- Create customer/tenant via UI or curl.
- Run whoami, CRUD.
- Run load test; inspect `SHOW POOLS`.
- View metrics in Prometheus/Grafana.
- Uninstall.

### 5. Makefile / skaffold (optional)

Targets: build-images, load-images, helm-install, helm-uninstall, seed, load-test.

### 6. Validation against success criteria

Run through overview §12 checklist; record results (maybe in README or a `docs/validation.md`).

## Acceptance criteria

- End-to-end demo works on a fresh cluster following README.
- Load test shows bounded connections (≤5 server per pool, ≤2 client per Hikari pool).
- Metrics visible in Prometheus/Grafana.
- README complete enough for a new user.

## Risks / notes

- Image loading for kind vs. registry; document both.
- Port-forward instructions must match Service names/ports.
- Grafana dashboard JSON must match metric names from Micrometer/PgBouncer exporter.
