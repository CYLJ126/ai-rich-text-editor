# Docker 部署说明

## 1. 修改配置

本文档中的命令默认都在 `docker/` 目录下执行：

```bash
cd docker
```

部署前先修改 `.env`。当前默认值使用 Docker Compose 内部服务名：

- MySQL：`mysql:3306`
- Redis：`redis:6379`
- Elasticsearch：`http://elasticsearch:9200`
- draw.io：`http://drawio:8080`（前端通过 `/drawio/` 同源代理访问）
- 前端 HTTPS 端口：`8000`

如需使用外部 draw.io 服务，修改 `.env` 中的 `NGINX_DRAWIO_UPSTREAM`，例如 `http://192.168.31.212:8080`。前端仍访问同源 `/drawio/`，由 nginx 转发到该地址。

如果使用外部已经搭建好的中间件，需要在 `.env` 中改这些配置：

```env
APP_DB_URL=jdbc:mysql://your-mysql-host:3306/arte?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
APP_DB_USERNAME=your_user
APP_DB_PASSWORD=your_password
CHLOROPHYLL_DB_URL=jdbc:mysql://your-mysql-host:3306/nip?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
CHLOROPHYLL_DB_USERNAME=your_user
CHLOROPHYLL_DB_PASSWORD=your_password
REDIS_ADDRESS=redis://your-redis-host:6379
REDIS_PASSWORD=
ELASTICSEARCH_URIS=http://your-es-host:9200
ELASTICSEARCH_USERNAME=elastic
ELASTIC_PASSWORD=your_es_password
KIBANA_ELASTICSEARCH_HOSTS=http://your-es-host:9200
```

如果外部中间件部署在 Docker 宿主机上，不要在容器配置里写 `localhost`。容器里的 `localhost` 指的是容器自己，应该改成宿主机局域网 IP，或者使用 `host.docker.internal`。

后端 jar 和前端 dist 包由 `compose-up.sh` 在构建前准备。必须显式配置来源，来源既可以是下载地址，也可以是 Docker 宿主机上的本地文件：

```env
# 本地文件，支持绝对路径或相对 arte-docker/ 目录的路径
ARTE_APP_JAR_SOURCE=../ai-rich-text-editor/arte-app/target/arte-app-boot.jar
ARTE_FRONT_DIST_SOURCE=../ai-rich-text-editor-front/dist.zip

# 本地绝对路径示例
# ARTE_APP_JAR_SOURCE=/opt/arte/arte-app-boot.jar
# ARTE_FRONT_DIST_SOURCE=/opt/arte/dist.zip

# 远程下载示例：每次启动都会重新下载
# ARTE_APP_JAR_SOURCE=https://example.com/arte-app-boot.jar
# ARTE_FRONT_DIST_SOURCE=https://example.com/dist.zip
```

每次脚本执行都会先清空上一次的构建产物，并复制或下载为包含新 UUID 的文件名，因此 Docker 的 `COPY` 层不会复用旧缓存。

Linux/macOS 使用 `sh ./compose-up.sh`；Windows PowerShell 使用 `./compose-up.ps1`，后续命令只需替换脚本前缀即可。

## 2. 启动方式

只启动后端和前端，适用于外部中间件模式：

```bash
sh ./compose-up.sh backend frontend
```

启动项目内置的全部中间件：

```bash
sh ./compose-up.sh --profile middleware --
```

只启动部分中间件：

```bash
sh ./compose-up.sh --profile mysql --profile redis --
sh ./compose-up.sh --profile es --
```

内置 MySQL 在全新 volume 下会自动执行挂载的 SQL 文件。如果使用外部 MySQL，或者 SQL 修改后需要重新执行初始化，可以手动运行：

```bash
docker compose --profile mysql-init run --rm mysql-init
```

使用内置 MySQL，并且希望按完整流程启动和初始化：

```bash
docker compose --profile mysql --profile redis --profile es up -d --build
docker compose --profile mysql-init run --rm mysql-init
```

## 3. Elasticsearch

内置 Elasticsearch 镜像由 `elasticsearch/Dockerfile` 构建，并在构建阶段安装 IK 插件：

```dockerfile
RUN bin/elasticsearch-plugin install --batch https://get.infini.cloud/elasticsearch/analysis-ik/8.13.4
```

Elasticsearch 第一次启动完成后，执行初始化脚本重置 `kibana_system` 用户密码：

```bash
./init-es.sh
```

Kibana 登录信息：

- 用户名：`elastic`
- 密码：`.env` 中的 `ELASTIC_PASSWORD`

## 4. 常用操作

停止某一个服务：

```bash
docker compose stop frontend
```

删除某一个服务容器：

```bash
docker compose rm -sf frontend
```

重新构建并重建某一个服务：

```bash
sh ./compose-up.sh frontend
```

删除所有容器，但保留 volume 数据：

```bash
docker compose down
```

删除所有容器和 volume 数据：

```bash
docker compose down -v
```
