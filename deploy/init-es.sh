#!/bin/bash

set -e

if [ -f .env ]; then
  export $(grep -E '^(ELASTIC_PASSWORD|KIBANA_PASSWORD)=' .env | xargs)
else
  echo "Missing .env file"
  exit 1
fi

ES_URL="${ES_URL:-http://localhost:9200}"
MAX_RETRIES="${MAX_RETRIES:-30}"
RETRY_INTERVAL="${RETRY_INTERVAL:-2}"

echo "Waiting for Elasticsearch..."

for ((i=1; i<=MAX_RETRIES; i++)); do
  if curl -fsS -u "elastic:${ELASTIC_PASSWORD}" "$ES_URL" >/dev/null 2>&1; then
    echo "Elasticsearch is ready"
    break
  fi
  echo "  retry $i/$MAX_RETRIES"
  sleep "$RETRY_INTERVAL"
done

if [ "$i" -gt "$MAX_RETRIES" ]; then
  echo "Elasticsearch startup timed out"
  exit 1
fi

echo "Setting kibana_system password..."

curl -fsS -X POST \
  -u "elastic:${ELASTIC_PASSWORD}" \
  "$ES_URL/_security/user/kibana_system/_password" \
  -H "Content-Type: application/json" \
  -d "{\"password\":\"${KIBANA_PASSWORD}\"}" >/dev/null

echo "Restarting Kibana..."
docker compose restart kibana >/dev/null

echo ""
echo "Done."
echo "Kibana: http://localhost:5601"
echo "Login user: elastic"
echo "Login password: ${ELASTIC_PASSWORD}"
