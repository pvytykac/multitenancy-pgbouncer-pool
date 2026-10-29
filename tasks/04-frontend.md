# Task 04 — Frontend: React (Vite) SPA

> Prerequisites: `00-overview`, `03-provisioner` (REST API contract). Timebox 1–2 days.

## Objective

A simple React SPA deployed as its own pod with Ingress, calling the provisioner REST
API to create customers/tenants and list them. Built with Vite; image built from
`frontend/`.

## Directory

```
frontend/
├── package.json
├── Dockerfile
├── vite.config.ts
├── index.html
└── src/
    ├── main.tsx
    ├── App.tsx
    └── api.ts
```

## Tech

- React 18+, TypeScript.
- Vite build.
- Fetch/axios for API calls.
- No heavy UI library required (plain CSS or a small one).

## Configuration

- Runtime ConfigMap holds the provisioner base URL; the same image works across
  environments. The container entrypoint substitutes it into the SPA config (or the
  SPA fetches `/config.json` at runtime). No build-time `VITE_API_BASE_URL`.
- Admin token entry (input field) stored in memory/localStorage for demo; sent as
  `X-Admin-Token` header.

## Features

1. **Customers view**
   - Form: create customer (name). Calls `POST /api/customers`.
   - List customers (`GET /api/customers`).
2. **Tenants view** (per customer)
   - Select customer; form create tenant (name). Calls
     `POST /api/customers/{c}/tenants`.
   - List tenants (`GET /api/customers/{c}/tenants`).
3. Show created DB name (`cust_<c>`), schema (`tenant_<t>`), role names.
4. Optional: seed button calling `POST /api/seed`.
5. Basic error display from API responses.

## Dockerfile

Multi-stage: node build → nginx (or serve static) runtime. Expose 80 (or 8080).
Nginx config for SPA fallback (try_files).

## Helm integration (task 05)

- `frontend-deployment.yaml` + Service + Ingress.
- `VITE_API_BASE_URL` baked at build; alternatively runtime config via ConfigMap.
  Document choice. For simplicity, build arg in Dockerfile.

## Acceptance criteria

- `npm run build` succeeds.
- Docker image serves SPA.
- Against a running provisioner, can create customer `acme` and tenant `t1` via UI.
- CORS works (provisioner allows frontend origin).

## Risks / notes

- CORS origin must match Ingress host; provisioner `CORS_ORIGINS` from values.
- Admin token in browser is demo-only; not production.
