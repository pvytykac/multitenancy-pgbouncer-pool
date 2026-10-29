#!/bin/sh
set -e

API_BASE_URL="${API_BASE_URL:-}"
SERVICES="${SERVICES:-order-service,inventory-service}"

services_json="[]"
if [ -n "$SERVICES" ]; then
  services_json="["
  first=1
  IFS=',' 
  for s in $SERVICES; do
    s=$(echo "$s" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    [ -z "$s" ] && continue
    if [ "$first" = "1" ]; then first=0; else services_json="$services_json,"; fi
    services_json="$services_json\"$s\""
  done
  services_json="$services_json]"
fi

cat > /usr/share/nginx/html/config.json <<EOF
{"apiBaseUrl":"${API_BASE_URL}","services":${services_json}}
EOF

exec nginx -g 'daemon off;'