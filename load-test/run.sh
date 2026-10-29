#!/usr/bin/env bash
# Run the k6 load test against a port-forwarded backend and capture PgBouncer
# SHOW POOLS before/after. Asserts sv_idle + sv_active <= MAX_SERVER (default 5)
# for the (database, user) pool under test.
set -euo pipefail

NAMESPACE="${NAMESPACE:-default}"
RELEASE="${RELEASE:-demo}"
CUSTOMER="${CUSTOMER:-acme}"
TENANT="${TENANT:-t1}"
MAX_SERVER="${MAX_SERVER:-5}"
BACKEND_LOCAL_PORT="${BACKEND_LOCAL_PORT:-18080}"
PGB_LOCAL_PORT="${PGB_LOCAL_PORT:-16432}"
BACKEND_SERVICE="${BACKEND_SERVICE:-${RELEASE}-order-service}"
PGB_ADMIN_USER="${PGB_ADMIN_USER:-pgbouncer_admin}"
PGB_ADMIN_PASSWORD="${PGB_ADMIN_PASSWORD:-demo-pgbouncer-admin-pw}"
DB_NAME="${DB_NAME:-cust_${CUSTOMER}}"
USER_NAME="${USER_NAME:-app_${TENANT}_orderservice}"

PF_PIDS=""
cleanup() {
  for pid in $PF_PIDS; do
    kill "$pid" 2>/dev/null || true
  done
}
trap cleanup EXIT

kubectl port-forward -n "$NAMESPACE" "svc/${BACKEND_SERVICE}" "${BACKEND_LOCAL_PORT}:80" >/tmp/ltpf-backend.log 2>&1 &
PF_PIDS="$PF_PIDS $!"
kubectl port-forward -n "$NAMESPACE" "svc/${RELEASE}-pgbouncer" "${PGB_LOCAL_PORT}:6432" >/tmp/ltpf-pgb.log 2>&1 &
PF_PIDS="$PF_PIDS $!"

# wait for ports
for i in $(seq 1 30); do
  if (echo >"/dev/tcp/127.0.0.1/${BACKEND_LOCAL_PORT}") 2>/dev/null && (echo >"/dev/tcp/127.0.0.1/${PGB_LOCAL_PORT}") 2>/dev/null; then
    break
  fi
  sleep 1
done

export PGPASSWORD="$PGB_ADMIN_PASSWORD"
show_pools() {
  psql -h 127.0.0.1 -p "$PGB_LOCAL_PORT" -U "$PGB_ADMIN_USER" -d pgbouncer -Atc "SHOW POOLS;" 2>/dev/null || true
}

echo "== SHOW POOLS (before) =="
show_pools | tee before-pools.txt

BASE_URL="http://127.0.0.1:${BACKEND_LOCAL_PORT}" \
CUSTOMER="$CUSTOMER" TENANT="$TENANT" \
  k6 run "$(dirname "$0")/k6.js"

echo "== SHOW POOLS (after) =="
show_pools | tee after-pools.txt

# Assert sv_idle+sv_active <= MAX_SERVER for the target pool.
awk -F'|' -v db="$DB_NAME" -v usr="$USER_NAME" -v max="$MAX_SERVER" '
  $1==db && $2==usr {
    sv_active=$5; sv_idle=$6;
    total=sv_active+sv_idle;
    printf "pool %s/%s: sv_active=%s sv_idle=%s total=%d (limit %d)\n", db, usr, sv_active, sv_idle, total, max;
    if (total > max) { bad=1 }
  }
  END { exit (bad?1:0) }
' after-pools.txt

echo "PASS: server connections for ${DB_NAME}/${USER_NAME} <= ${MAX_SERVER}"
