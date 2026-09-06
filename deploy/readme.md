# Docker 部署

这套部署采用成熟开源项目常见的“CI 构建镜像，Compose 只负责运行”模式：

- GitHub Release 发布时，GitHub Actions 构建 linux/amd64、linux/arm64 镜像并推送到 GHCR。
- Release 同时附带完整的 deploy 压缩包。
- 使用者不需要 Java、Node.js、Maven 或 npm，只需要 Docker Engine / Docker Desktop 和 Compose 插件。
- 仓库开发者可用一个参数切换为本地源码构建，产出的运行结构与发布镜像一致。

## 一键启动

在 deploy 目录运行：

    # Windows PowerShell
    .\compose-up.ps1

    # Linux / macOS
    ./compose-up.sh

脚本第一次运行会把 .env.example 复制为 .env，等待 MySQL、Redis、Elasticsearch、后端和前端全部健康后才返回成功。访问：

    http://localhost:8000

.env.example 中的数据库等服务密码是本机验证默认值，部署到公网前必须修改 MySQL、Elasticsearch 及可选 Kibana 的密码。SM2 和 JWT 密钥无需手工编写，首次启动自动生成并持久化。

## 初始化与首次登录

全新数据库会导入管理员 `admin`，初始密码为 `Aa111111`，以及启用状态的角色和菜单。首次登录后请立即修改密码。`JWT_DEFAULT_PASSWORD` 是应用重置密码/创建用户时使用的默认值，不会改写 DML 中管理员的密码哈希。

首次初始化还会创建公共目录“ARTE 使用指南”，将 `118-ARTE简介.md` 和 `119-ARTE全部特性.md` 写入其中，并通过一次性 Compose 服务把两篇文章的检索元数据写入 Elasticsearch。Release 部署包已经包含这两个源文件；直接复制 `deploy` 目录时，也必须保留仓库中的 `docs/public` 相对目录结构。

初始化 DDL、DML 只在 MySQL 数据卷为空时执行；更新 SQL 文件不会更新已有数据库，也不要为应用这次修改而删除真实数据。MySQL 首次初始化会留下一次性标记，Elasticsearch 服务只在该标记存在时写入两篇文章，成功后不会在每次启动重复执行。它不会替已有 MySQL 数据补跑文章初始化。本次修正了初始角色禁用状态、文章字段名称（`character_count`）以及 MySQL 初始化客户端字符集（避免中文乱码）。已有安装如有旧字段、旧角色数据或已导入的乱码，需要先备份再按实际情况迁移，新配置不会修复已存储的乱码。

## SM2 / JWT 密钥自动生成

在 `.env` 中留空 `SECURITY_SM2_PRIVATE_KEY`、`SECURITY_SM2_PUBLIC_KEY`、`JWT_BASE64_SECRET_KEY` 即可。后端首次启动生成 SM2 密钥对和 64 字节随机 JWT 签名密钥，保存在 `backend-secrets` 数据卷中；镜像构建、重启和容器重建都复用原值，不会每次更换。不同新安装生成不同密钥，私密文件仅文件所有者可读写，不进入镜像或日志。

已有 `.env` 显式指定的密钥仍会导入并保存，升级不会偷偷轮换旧密钥。只指定 SM2 私钥也可自动推导公钥；同时指定时会检查是否配对。请将 `backend-secrets` 与数据库一起备份；删除该卷且不提供原密钥会导致重新生成、旧登录令牌失效。更改 Compose 项目名也会切换到另一组数据卷。

## 自带 HTTPS 证书，同时保留 HTTP

将 PEM 格式的完整证书链和未加密私钥放在 `deploy/certs/`（已被 Git 忽略），在 `.env` 设置：

```dotenv
ARTE_PORT=8000
ARTE_HTTPS_PORT=8443
ARTE_TLS_CERT_FILE=./certs/fullchain.pem
ARTE_TLS_KEY_FILE=./certs/privkey.pem
```

证书在其他目录时填写绝对路径，Windows 建议使用正斜杠。证书需匹配实际访问域名。启动命令：

    .\compose-up.ps1 -Https
    ./compose-up.sh --https

本地源码构建时加上 `-BuildLocal` / `--build-local`。HTTP 仍通过 `http://localhost:8000` 访问；HTTPS 使用 `https://你的域名:8443`，端口均可修改。不强制将 HTTP 跳转到 HTTPS；如只允许内网使用 HTTP，请通过防火墙限制该端口。未传 HTTPS 参数时不需要证书。

HTTPS 使用 TLS 1.2/1.3，网页、接口和 WebSocket 共用同一个 Nginx 入口。证书续期由使用者负责；替换证书后重建前端容器，让只读挂载重新读取文件：

    docker compose -f docker-compose.yml -f docker-compose.https.yml up -d --no-deps --force-recreate frontend

源码构建部署需在 HTTPS 文件前再加 `-f docker-compose.build.yml`。之后更新或重建前端也必须携带 HTTPS 覆盖文件，否则会移除 HTTPS 配置。参考 [Nginx 官方 HTTPS 配置](https://nginx.org/en/docs/http/configuring_https_servers.html)。

## 从当前源码构建

尚未发布 GHCR 镜像，或者需要验证当前修改时：

    .\compose-up.ps1 -BuildLocal

    ./compose-up.sh --build-local

本地构建使用 docker-compose.build.yml 覆盖镜像来源。后端与前端都在多阶段 Dockerfile 中编译，宿主机不需要安装构建工具。

## 使用现有 MySQL 和 Elasticsearch

外部依赖模式只启动后端、前端、Redis 和 Draw.io，不会创建 MySQL、Elasticsearch 或执行初始化 DDL/DML。默认直接读取源码仓库的 `backend/profile/app.properties`，并仅使用其中的两组数据源和 Elasticsearch 配置；密钥、存储路径等容器配置仍由 Compose 管理。

    .\compose-up.ps1 -External
    ./compose-up.sh --external

本地源码构建：

    .\compose-up.ps1 -External -BuildLocal
    ./compose-up.sh --external --build-local

配置文件在其他位置时，在 `.env` 设置 `ARTE_BACKEND_CONFIG_FILE`；Release 部署包不包含你的私有后端配置，因此必须提供该文件。容器需能访问配置中的 MySQL/Elasticsearch 地址；如果服务在 Docker 宿主机上，请在配置中使用 `host.docker.internal` 或宿主机局域网 IP，不要使用 `localhost`。

## 可选 Kibana

    .\compose-up.ps1 -Observability

    ./compose-up.sh --observability

Kibana 默认只监听 127.0.0.1:5601。Compose 会自动设置 kibana_system 密码，不需要再手工运行 Elasticsearch 初始化脚本。

本地源码构建并同时启动 Kibana：

    .\compose-up.ps1 -BuildLocal -Observability

## 更新和运维

更新已发布镜像：

    docker compose pull
    docker compose up -d --wait

查看状态和日志：

    docker compose ps
    docker compose logs -f backend

停止服务并保留数据：

    docker compose down

删除数据卷会永久删除数据库、索引、上传文件和自动生成的密钥：

    docker compose down -v

MySQL 初始化 SQL 只会在全新 mysql-data volume 上运行。旧版 nip 数据升级请先备份，再按需使用 mysql/migrate-nip-to-arte.sql，不要把迁移脚本放进自动初始化目录。

## 镜像发布

.github/workflows/docker-publish.yml 在 GitHub Release 发布后生成以下镜像：

- ghcr.io/cylj126/ai-rich-text-editor-backend
- ghcr.io/cylj126/ai-rich-text-editor-frontend
- ghcr.io/cylj126/ai-rich-text-editor-elasticsearch

标签包含完整版本、主次版本、latest 和提交 SHA，并附带 SBOM 与 provenance。首次发布后，需要在 GitHub Packages 设置中把三个包改为 Public，匿名用户才能直接拉取。

## 常见问题

- 请使用 docker compose，不要使用已废弃的 docker-compose。
- docker compose up --wait 返回非零时，先运行 docker compose ps 和 docker compose logs SERVICE。
- Docker Desktop 拉取 Docker Hub 出现 EOF 时，检查 Docker Desktop 的代理设置；这是引擎网络问题，不应把临时镜像站地址提交进 Compose。
- 数据库和 Elasticsearch 不暴露宿主机端口。需要排查时使用 docker compose exec。
