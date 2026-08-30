# Docker Deployment

## 1. Configure

Run all commands in this document from the `docker/` directory:

```bash
cd docker
```

Edit `.env` first. The default values use Compose service names:

- MySQL: `mysql:3306`
- Redis: `redis:6379`
- Elasticsearch: `http://elasticsearch:9200`
- draw.io: `http://drawio:8080` (the frontend accesses it through the same-origin `/drawio/` proxy)
- Frontend HTTPS port: `8000`

To use an external draw.io service, change `NGINX_DRAWIO_UPSTREAM` in `.env`, for example `http://192.168.31.212:8080`. The frontend still uses same-origin `/drawio/`, and nginx forwards it to that upstream.

To use external middleware, change these values in `.env`:

```env
APP_DB_URL=jdbc:mysql://your-mysql-host:3306/arte?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
APP_DB_USERNAME=your_user
APP_DB_PASSWORD=your_password
CHLOROPHYLL_DB_URL=jdbc:mysql://your-mysql-host:3306/eladmin?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
CHLOROPHYLL_DB_USERNAME=your_user
CHLOROPHYLL_DB_PASSWORD=your_password
REDIS_ADDRESS=redis://your-redis-host:6379
REDIS_PASSWORD=
ELASTICSEARCH_URIS=http://your-es-host:9200
ELASTICSEARCH_USERNAME=elastic
ELASTIC_PASSWORD=your_es_password
KIBANA_ELASTICSEARCH_HOSTS=http://your-es-host:9200
```

If the external service is running on the Docker host machine, use the host LAN IP or `host.docker.internal` instead of `localhost`.

The backend jar and frontend dist package are prepared by `compose-up.sh` before the image build. Both sources must be configured explicitly, and each source can be a download URL or a local file:

```env
# Local files, absolute paths or paths relative to arte-docker/
ARTE_APP_JAR_SOURCE=../ai-rich-text-editor/arte-app/target/arte-app-boot.jar
ARTE_FRONT_DIST_SOURCE=../ai-rich-text-editor-front/dist.zip

# Absolute local path examples
# ARTE_APP_JAR_SOURCE=/opt/arte/arte-app-boot.jar
# ARTE_FRONT_DIST_SOURCE=/opt/arte/dist.zip

# Download URL examples: downloaded again on every startup
# ARTE_APP_JAR_SOURCE=https://example.com/arte-app-boot.jar
# ARTE_FRONT_DIST_SOURCE=https://example.com/dist.zip
```

Each script invocation clears the previous staged artifact and copies or downloads a file with a new UUID in its name. This guarantees that Docker builds a new `COPY` layer.

Use `sh ./compose-up.sh` on Linux/macOS, or `./compose-up.ps1` in Windows PowerShell; the remaining arguments are the same.

## 2. Start Modes

Only backend and frontend, for the external middleware mode:

```bash
sh ./compose-up.sh backend frontend
```

Start all bundled middleware:

```bash
sh ./compose-up.sh --profile middleware --
```

Start selected middleware:

```bash
sh ./compose-up.sh --profile mysql --profile redis --
sh ./compose-up.sh --profile es --
```

The bundled MySQL runs mounted SQL files automatically on a fresh volume. Run MySQL initialization explicitly when using an external MySQL, or when rerunning SQL after changes:

```bash
docker compose --profile mysql-init run --rm mysql-init
```

When using the bundled MySQL and initializing in one flow:

```bash
docker compose --profile mysql --profile redis --profile es up -d --build
docker compose --profile mysql-init run --rm mysql-init
```

## 3. Elasticsearch

The bundled Elasticsearch image is built from `elasticsearch/Dockerfile` and installs the IK plugin during image build:

```dockerfile
RUN bin/elasticsearch-plugin install --batch https://get.infini.cloud/elasticsearch/analysis-ik/8.13.4
```

After Elasticsearch is up the first time, reset the Kibana system user password:

```bash
./init-es.sh
```

Kibana login uses:

- Username: `elastic`
- Password: `.env` value `ELASTIC_PASSWORD`

## 4. Useful Operations

Stop one service:

```bash
docker compose stop frontend
```

Remove one service container:

```bash
docker compose rm -sf frontend
```

Rebuild and recreate one service:

```bash
sh ./compose-up.sh frontend
```

Remove all containers but keep volumes:

```bash
docker compose down
```

Remove containers and volumes:

```bash
docker compose down -v
```
