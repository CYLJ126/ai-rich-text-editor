#!/bin/sh
set -eu

if [ ! -f /init-state/public-docs.pending ]; then
  echo "Public documentation Elasticsearch initialization is not pending; skipped."
  exit 0
fi

timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
sed "s/__TIMESTAMP__/${timestamp}/g" /init/public-docs.ndjson > /tmp/public-docs.ndjson

response="$(curl -fsS \
  -u "elastic:${ELASTIC_PASSWORD}" \
  -H 'Content-Type: application/x-ndjson' \
  --data-binary @/tmp/public-docs.ndjson \
  'http://elasticsearch:9200/_bulk?refresh=true')"

if ! printf '%s' "${response}" | grep -q '"errors":false'; then
  printf '%s\n' "${response}" >&2
  exit 1
fi

rm /init-state/public-docs.pending
touch /init-state/public-docs.completed
echo "Public documentation metadata initialized in Elasticsearch."
