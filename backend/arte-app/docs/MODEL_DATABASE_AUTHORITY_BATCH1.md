> 当前版本已移除固定文件模式和模式选择键；部署升级以 [数据库单一来源](AI_DATABASE_ONLY.md) 为准。本文保留阶段性设计与验收记录。

# 数据库模型管理第一批：唯一来源、空目录与审核连接页面

> 后继设计（2026-11-03）：[模型配置重构 M0](../../../ARTE%20AI%20模型配置需求及设计.md) 已定稿，M1～M5 尚未编码。本文保留当批实现与验证事实；后续
> DATABASE 新建模型采用审核方案／实际资源分离及独立空间预算契约，不按旧目录直供或分散创建流程实施新批次。
>
> 当前 DATABASE 模式的出口白名单由[平台模型出口策略](PLATFORM_AI_CONTROL.md#平台模型出口策略)管理。下文的部署出口配置属于当批记录。

日期：2026-11-01。

## 交付边界

本批在已有模型创建、草稿、个人凭据和发布链路上，完成 DATABASE 的配置来源切换及平台审核连接管理。
租户／空间政策页面、独立模型／费率发布方案、租户模型和跨空间授权页面仍属于后续批次。

- DATABASE 的连接、绑定、能力、费率、旧授权及预算定义只取数据库。head=0 返回空定义，不能回退部署文件。
- 缺表、发布指针缺失、数据库不可读或引用修订缺失均报错，不能伪装成合法空目录。启动核验不执行 DDL、导入或回填。
- 删除旧的 `importConfiguration` HTTP，增加显式 `initializeCatalog`。初始化只发布关闭默认 AI 的空目录，不产生模型、授权或预算。
- 初次发布连接也可以直接从 head=0 建立第一份目录，无需先初始化。操作明确由有资格的用户发起。
- 连接管理复用 `arte_ai_control_revision` 的不可变修订及 `arte_ai_control_head`，本批没有新增表或后台自动迁移。
- 原 FIXED 来源标记仍可保留历史来源语义；DATABASE 下关联其精确定义时也查询数据库目录，绝不读取文件。

## 页面及入口

`/AI/Connections`：平台审核连接管理。模型管理页及 ChatNew 的“平台 AI 配置”抽屉提供“管理审核连接”入口。 该页面不依赖某个空间
AI 已准备或已有预算；后端要求平台 `aiConfiguration:manage`／admin，且在同库事务重查当前 RBAC。 租户或空间的 OWNER／ADMIN
不会隐式获得此全平台权限。

页面提供连接标识、供应商、基础地址、连接／响应超时、响应字节限制，以及目录重新读取、版本历史、编辑为新版本、启停。 本批只开放已实现的
`deepseek + chat-completions-sse@v1`。保存登记不会探测供应商或伪造连接验证成功；模型草稿原验证流程保持有效。 页面不接收或保存
API Key、SecretRef。个人密钥仍在模型草稿页单独提供，并用环境注入的 SM4 主密钥加密入库。

## 连接契约

全部 POST，路径前缀 `/arte/workspace/ai`；写操作携带 `Idempotency-Key`，沿用 ResultContext。

| 端点                 | 请求                             | 结果                                                  |
|----------------------|----------------------------------|-------------------------------------------------------|
| getConnections       | `{}`                             | `{version, initialized, connections, allowedOrigins}` |
| publishConnection    | `{expectedVersion, settings}`    | 连接目录的命令回执                                    |
| setConnectionEnabled | `{expectedVersion, id, enabled}` | 连接目录的命令回执                                    |
| initializeCatalog    | 无业务请求字段                   | 空目录的命令回执；仅在 head=0 首次执行                |

`settings` 示例（不含凭据）：

```json
{
  "id": "reviewed-deepseek",
  "providerId": "deepseek",
  "endpoint": "https://api.deepseek.com/v1",
  "connectTimeoutSeconds": 5,
  "responseTimeoutSeconds": 120,
  "maxResponseBytes": 1048576
}
```

服务端生成 `connection:<id>@db-v<新目录修订号>`。相同标识再次发布会追加新版本，不能覆盖或删除旧版本。 发布、回执、Outbox
和审计在同一事务内提交；CAS 冲突不隐式覆盖。未知字段（含嵌套密钥字段）拒绝。 命令重放重新核验当前权限，返回原操作与原连接版本，不以新键创建竞争版本。

目录新增可选 `disabledConnections` 集合：按稳定连接标识停用全部精确版本，旧修订缺少该字段时解释为空集合。
历史连接内容保持不可变，当前配置解析叠加停用状态。模型模板发现和个人发布运行检查继续复核此当前状态；已有执行历史与账本不改写。
已有目录的通用 `publishCatalog` 仍不能修改连接集合或启停集合，这两项必须经过专用命令。

## 策略与配置来源

发布连接必须满足部署 `arte.ai-new-generation.allowed-origins`。页面显示实际允许的 origin，不通过 UI 任意扩大部署出口权限。
HTTP (S) 地址不能包含 userinfo、查询参数或片段；原 DNS／地址检查、禁止重定向等规则继续生效。 本批未将部署出口硬上限迁移为租户政策，也没有放宽它。

审核连接出现在模型草稿的模板下拉框，还要求租户和空间同时允许个人凭据、协议、供应商、目的地主机。 空白名单继续拒绝。两级政策仍通过既有治理
HTTP 配置；第二批已增加[租户与空间政策页面](MODEL_DATABASE_AUTHORITY_BATCH2.md)。第一批交付边界仍按上述记录保留。

默认 `application-ai.yml` 导入的运行配置文件已移除六类模型业务属性：
`arte.ai-new.connections/bindings/capabilities/rates/grants/budgets`。 DATABASE 可删除 app.properties
中这些旧键；即使构建参数仍存在，也不会被默认运行资源导入。
`app.properties.example` 已移除对应业务定义，仅保留部署设置。 若旧 `arte.platform.identity.bindings[*].subject-id` 引用了
`${arte.ai-new.grants[*].subject-id}`，删除旧键前须把原来的同一稳定 subjectId
显式保留在身份映射中；不能重新生成主体或把模型迁移当作身份迁移。新版示例已去除此交叉占位符。

旧 FIXED 部署需显式启用 `ai-fixed` Spring profile，并使用 `fixed-models.properties.example` 的示例参数。 该 profile 单独导入
`fixed-definitions.properties`；DATABASE 与 ai-fixed 混用会拒绝启动。 数据库地址、线程／超时硬上限、功能开关和
`ARTE_MODEL_CREDENTIAL_KEY` 等部署参数继续由环境或配置提供。

## 使用与升级

1. 安装既有公共平台及 AI 控制面等所需 DDL；本批不会操作业务库。
2. 设置 DATABASE 并重新构建后端；已有数据库目录保留，启动不会导入或覆盖它。
3. 以平台配置管理员打开 `/AI/Connections`，创建连接。空库可以直接发布第一条连接。
4. 核对租户／空间政策后，在“我可管理”的模型草稿页重新读取模型并选择审核连接。
5. 修改连接时发布新版本；启停作用于同标识全部版本。应用重新启动后继续读取相同数据库记录。

已有业务目录及个人发布无需从文件重建。若模型以前只存在于配置文件、尚未保存到数据库，本批不会自动迁移这些定义；
需显式登记或在后续迁移批次核对后导入。已有发布关联、历史执行和费用记录不改 owner、scope 或固定引用。

浏览器写操作先将白名单请求和原键保存在按账号隔离的 sessionStorage；存储失败不发送新命令。
刷新后只恢复操作，不自动提交。网络、响应无法确认、鉴权暂不可用及幂等内容冲突时保留原请求重试；普通版本冲突需重新读取后明确提交。

## 验证

`DatabaseConnectionIntegrationTest` 覆盖隔离数据库中的空目录启动、文件种子不回退、初始化与重放、版本保留、重新装配后读取、
两级政策过滤、连接启停、权限撤销、数据库故障、审计失败回滚及真实 MVC 的字段／权限约束。
前端服务与组件测试覆盖白名单请求、无权限展示、CAS、恢复存储失败、刷新后的原键重试和账号隔离。 个人模型、模型 ACL、既有控制面、ChatNew
和空间配置继续执行受影响的定向回归。 具体执行结果以本次交付说明及测试报告为准，不把隔离 H2／回环供应商验证视为业务 MySQL
或生产多节点验收。

本次定向结果（按用例去重，包含修正后复验）：后端 72 项通过，1 项隔离 MySQL 专用用例跳过；前端 79 项通过。 空目录启动另以实际生成网关和
HTTP 运行时装配验证，未提供任何文件模型定义。 前端生产构建生成 `AI/Connections` 路由，新增连接组件与服务 Biome lint 通过；全项目
TypeScript 仍有既有 106 项诊断，变更文件无诊断。 前端复验包含一次并行运行超时的单项重跑，以及把既有版本按钮测试选择器改为允许文案空格差异；未改变对应业务行为。
未重启现有业务节点、执行业务数据库 DDL 或调用真实供应商，未计入真实浏览器／生产多节点验收。
