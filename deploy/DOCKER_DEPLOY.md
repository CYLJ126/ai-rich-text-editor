# Docker Deployment

This deployment follows the release-image pattern used by established open-source projects:

- Publishing a GitHub Release builds linux/amd64 and linux/arm64 images and pushes them to GHCR.
- The release includes a complete deploy bundle.
- End users only need Docker Engine or Docker Desktop with the Compose plugin.
- Contributors can switch the same stack to local source builds with one flag.

## Start

Run from the deploy directory:

    # Windows PowerShell
    .\compose-up.ps1

    # Linux / macOS
    ./compose-up.sh

On the first run, the script copies .env.example to .env. It returns success only after all core services are healthy. Open http://localhost:8000.

The example file contains local-development service passwords. Replace the MySQL, Elasticsearch and optional Kibana passwords before exposing the service publicly.

## First login and initialization

An empty database is seeded with the `admin` account (initial password: `Aa111111`), enabled roles and menus. Change this password immediately after first login. `JWT_DEFAULT_PASSWORD` controls the application's reset/new-user default; it does not change the password hash in the seed SQL.

The first initialization also creates the public `ARTE 使用指南` catalog and imports `118-ARTE简介.md` and `119-ARTE全部特性.md`. A one-shot Compose service writes their search metadata to Elasticsearch before the frontend starts. The release bundle contains both source files. If you copy `deploy` directly, preserve the repository's adjacent `docs/public` directory structure.

The initialization DDL and DML only run on an empty MySQL volume. Updating these files does not update an existing database; do not delete a real database to apply them. Fresh MySQL initialization leaves a one-time marker for the Elasticsearch service, which is removed after the two documents are indexed, so subsequent starts skip this step. It does not backfill the articles into an existing MySQL database. This revision fixes the disabled seed roles, the article column name (`character_count`) and the MySQL client character set to preserve Chinese seed text. Existing installations need a reviewed migration for outdated schema/data or already-corrupted text; the new character-set setting does not repair stored text.

## Persistent encryption keys

Leave `SECURITY_SM2_PRIVATE_KEY`, `SECURITY_SM2_PUBLIC_KEY` and `JWT_BASE64_SECRET_KEY` empty. At first backend startup, the container generates an SM2 key pair and a random 64-byte JWT signing secret in the `backend-secrets` volume. Rebuilds, restarts and container recreation reuse them; each new installation gets independent keys. Private files are owner-readable only and are not baked into images or printed in logs.

Existing explicit values in `.env` are still imported and persisted; upgrading does not silently rotate old keys. A private key alone is sufficient to derive its public key. When both are supplied, they must match. Back up `backend-secrets` together with the database. Deleting it without supplying the original keys rotates credentials and invalidates existing tokens; changing the Compose project name also selects different volumes.

## HTTPS with your own certificate (HTTP remains available)

Place a PEM certificate chain and its unencrypted private key in `deploy/certs/` (ignored by Git), then set these values in `.env`:

```dotenv
ARTE_PORT=8000
ARTE_HTTPS_PORT=8443
ARTE_TLS_CERT_FILE=./certs/fullchain.pem
ARTE_TLS_KEY_FILE=./certs/privkey.pem
```

Use absolute paths (forward slashes on Windows) for certificates outside `deploy`. The certificate must cover the hostname you use.

    .\compose-up.ps1 -Https
    ./compose-up.sh --https

For local source builds, add `-BuildLocal` / `--build-local`. Open `http://localhost:8000` or `https://your-domain:8443`. HTTP stays available without a forced redirect; TLS 1.2/1.3 is served by the same Nginx server, including API and WebSocket proxying. Restrict the HTTP port with a firewall if it should be internal-only. Without the HTTPS flag, no certificate is needed.

Certificate renewal is managed by the user, not by this stack. After replacing certificate files, recreate the frontend to refresh read-only bind mounts:

    docker compose -f docker-compose.yml -f docker-compose.https.yml up -d --no-deps --force-recreate frontend

For local builds also include `-f docker-compose.build.yml` before the HTTPS file. Always include the HTTPS overlay when upgrading/recreating the frontend or HTTPS will be removed. See [Nginx HTTPS configuration](https://nginx.org/en/docs/http/configuring_https_servers.html).

## Build the current checkout

    .\compose-up.ps1 -BuildLocal

    ./compose-up.sh --build-local

docker-compose.build.yml replaces the published images with multi-stage source builds. Java, Node.js, Maven, and npm are not required on the host.

## Existing MySQL and Elasticsearch

External dependency mode starts only the backend, frontend, Redis and Draw.io. It does not create MySQL or Elasticsearch containers and does not run initialization DDL/DML. By default it reads `backend/profile/app.properties` from a source checkout and imports only its two datasource groups and Elasticsearch settings. Container secrets and storage paths remain managed by Compose.

    .\compose-up.ps1 -External
    ./compose-up.sh --external

For a local source build:

    .\compose-up.ps1 -External -BuildLocal
    ./compose-up.sh --external --build-local

Set `ARTE_BACKEND_CONFIG_FILE` in `.env` when the file is elsewhere. A release bundle does not include private backend configuration, so that path must be supplied. The container must be able to reach the configured database and Elasticsearch addresses. For services on the Docker host, use `host.docker.internal` or a LAN address instead of `localhost`.

## Optional Kibana

    .\compose-up.ps1 -Observability

    ./compose-up.sh --observability

Kibana binds to 127.0.0.1:5601 by default. Compose configures the kibana_system password automatically.

Use both flags to build locally and start Kibana:

    .\compose-up.ps1 -BuildLocal -Observability

## Upgrade and operate

    docker compose pull
    docker compose up -d --wait
    docker compose ps
    docker compose logs -f backend
    docker compose down

docker compose down -v permanently removes the database, indexes, uploaded files and generated security keys.

MySQL initialization files run only when mysql-data is empty. Back up an existing installation before using mysql/migrate-nip-to-arte.sql.

## Image publishing

.github/workflows/docker-publish.yml publishes:

- ghcr.io/cylj126/ai-rich-text-editor-backend
- ghcr.io/cylj126/ai-rich-text-editor-frontend
- ghcr.io/cylj126/ai-rich-text-editor-elasticsearch

Release tags include the full version, major/minor version, latest, and commit SHA. Builds include SBOM and provenance attestations. After the first release, set all three GitHub Packages to Public so anonymous Compose pulls work.
