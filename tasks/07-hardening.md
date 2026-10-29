# Task 07 — Phase 5: Hardening (in scope)

> Prerequisites: tasks 02–06. Promoted to in-scope per spike decisions: cleanup
> endpoints included in initial implementation. NetworkPolicies remain optional.

## Objective

Add cleanup endpoints and NetworkPolicies; improve robustness for demo/reuse.

## Deliverables

### 1. Cleanup endpoints in provisioner

- `DELETE /api/customers/{c}` → drop DB `cust_<c>`, remove registry row (and its
  tenants).
- `DELETE /api/customers/{c}/tenants/{t}` → drop schema `tenant_<t>`, drop roles
  `app_<t>_<service>` for all configured services, remove registry row.
- Guard with admin token; idempotent.

### 2. NetworkPolicies (Helm templates, optional, disabled by default)

- Allow backends → pgbouncer (6432).
- Allow provisioner → postgres (5432).
- Allow exporter → pgbouncer (6432).
- Allow ingress → frontend/provisioner/backends (8080/80).

### 3. Robustness improvements

- Pool eviction strategy for production note (not implemented in demo, but document).
- Raise Postgres `max_connections` via Bitnami values if needed; document.
- PgBouncer `max_db_connections` tuning note.
- Secret rotation documentation.

### 4. Additional tests

- Cleanup endpoint integration tests.
- NetworkPolicy rendering tests (helm template).

## Acceptance criteria

- Cleanup endpoints remove DB/schema/roles and registry rows.
- NetworkPolicies render when enabled and don't break install when disabled.
- Documentation updated.

## Risks / notes

- Dropping DBs/roles is destructive; require admin token and confirmation param.
- `DROP DATABASE` requires no active connections; may need terminate backends.
