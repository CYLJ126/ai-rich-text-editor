#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

build_local=false
external=false
observability=false
https=false

while [ "$#" -gt 0 ]; do
  case "$1" in
    --https)
      https=true
      shift
      ;;
    --build-local)
      build_local=true
      shift
      ;;
    --external)
      external=true
      shift
      ;;
    --observability)
      observability=true
      shift
      ;;
    --)
      shift
      break
      ;;
    *)
      break
      ;;
  esac
done

if [ "$external" = true ] && [ "$observability" = true ]; then
  echo "Observability profile is only available with bundled Elasticsearch." >&2
  exit 1
fi

if [ "$external" = true ]; then
  base_compose_file=docker-compose.external.yml
  build_compose_file=docker-compose.external.build.yml
else
  base_compose_file=docker-compose.yml
  build_compose_file=docker-compose.build.yml
fi

if [ ! -f .env ] && [ -f .env.example ]; then
  cp .env.example .env
  echo "Created deploy/.env from .env.example. Change the default passwords before exposing the service publicly."
fi

compose() {
  if [ "$observability" = true ]; then set -- --profile observability "$@"; fi
  if [ "$https" = true ]; then set -- -f docker-compose.https.yml "$@"; fi
  if [ "$build_local" = true ]; then set -- -f "$build_compose_file" "$@"; fi
  docker compose -f "$base_compose_file" "$@"
}

compose config --quiet

if [ "$build_local" = true ]; then
  compose up -d --wait --wait-timeout 900 --build --pull never "$@"
else
  compose up -d --wait --wait-timeout 900 "$@"
fi

arte_binding=$(compose port frontend 8080 | sed -n '1p')
arte_port=${arte_binding##*:}
echo
echo "AIRichTextEditor is ready: http://localhost:${arte_port}"
if [ "$https" = true ]; then
  https_binding=$(compose port frontend 8443 | sed -n '1p')
  echo "HTTPS is ready: https://localhost:${https_binding##*:}"
fi
if [ "$observability" = true ]; then
  kibana_binding=$(compose port kibana 5601 | sed -n '1p')
  kibana_port=${kibana_binding##*:}
  echo "Kibana is ready: http://localhost:${kibana_port}"
fi
