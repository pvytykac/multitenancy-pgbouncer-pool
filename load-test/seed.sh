#!/usr/bin/env bash
# Seed customers/tenants via the provisioner.
set -euo pipefail

PROVISIONER_URL="${PROVISIONER_URL:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_TOKEN:-demo-admin-token}"
CUSTOMERS="${CUSTOMERS:-3}"
TENANTS="${TENANTS:-2}"

curl -sS -X POST "${PROVISIONER_URL}/api/seed" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: ${ADMIN_TOKEN}" \
  -d "{\"customers\":${CUSTOMERS},\"tenants\":${TENANTS}}"
echo
