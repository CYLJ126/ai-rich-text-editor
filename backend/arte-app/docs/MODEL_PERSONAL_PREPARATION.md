# 个人模型管理 4-01：配置草稿、凭据与连接验证

日期：2026-10-31。

本批完成后端准备流程，复用稳定模型身份及公共 ACL。普通用户可创建身份、保存草稿、维护自己提供的凭据并验证连接，不要求平台
aiConfiguration:manage。 草稿和凭据不进入既有发布目录，不修改执行归属、预算、历史会话或已有模型版本。个人发布及聊天使用属于
4-02，管理页面属于 4-03，文章改造不在本批范围。

**术语**

| 术语 | 全称                                   | 中文含义             | 在当前代码中的用途                                     |
|------|----------------------------------------|----------------------|--------------------------------------------------------|
| ACL  | Access Control List                    | 访问控制列表         | 判断谁能读取、编辑、验证或使用某个模型                 |
| GCM  | Galois/Counter Mode                    | 伽罗瓦／计数器模式   | 配合 SM4，加密 API Key，并检测密文及关联数据是否被篡改 |
| HMAC | Hash-based Message Authentication Code | 基于哈希的消息认证码 | 生成稳定的敏感字段指纹，以及校验分页游标               |
| AAD  | Additional Authenticated Data          | 附加认证数据         | 将密文绑定到指定租户、空间、模型、责任主体和凭据版本   |

## 模块和数据

- arte-ai：ModelDraftService、默认应用服务、配置／验证值对象、存储／连接模板／加密／验证 SPI，以及复用现有出口规则的 HTTP 探测适配器。
- arte-app：独立 MyBatis 持久化、SM4-GCM、部署审核模板及政策适配、Spring 装配和 HTTP。
- arte-core：继续负责身份、成员／政策、公共授权、治理门控、回执、审计和 Outbox，没有引入 AI 类型。

新增 arte_ai_model_draft、arte_ai_model_credential、arte_ai_model_validation 三表，并为模型资源表增加空间候选索引。
[`arte-ai-model-draft-ddl-mysql.sql`](../scripts/arte-ai-model-draft-ddl-mysql.sql)须在公共 ACL 和模型资源 DDL
后执行一次；应用不自动建表、回填或激活空间。

模型身份及发布状态、草稿版本、凭据版本独立。首次草稿／凭据写入 expectedVersion=0，成功版本为 1；以后 CAS
递增。修改已发布资源的草稿不改变旧发布；草稿名称也是待发布配置，不直接改写已有资源名称。 业务数据、回执、审计和 Outbox
同事务提交，审计失败整体回滚。HTTP 不接受 owner、actor、createdBy、责任主体、SecretRef、endpoint 或验证结论。

## 权限和连接模板

模型列表是当前空间的管理元数据列表，按当前 model:read 逐项过滤，不按 createdBy 或 model:use 代替读取授权。 每次最多检查 100
个候选、返回 size 个可读项；允许空页带 nextCursor，不返回隐藏资源数量。游标绑定稳定主体及 scope，只有不可逆索引键和 HMAC。
排序按稳定资源索引键，不承诺按更新时间排序或跨请求一致快照。

| 操作           | 要求                                                           |
|----------------|----------------------------------------------------------------|
| 读取／保存草稿 | 当前 model:read／model:edit                                    |
| 首次提供凭据   | 当前 model:edit、个人凭据政策及有效模板；服务端记录责任主体    |
| 替换凭据       | 同时持有当前 model:edit 且是原责任主体                         |
| 撤销凭据       | 原责任主体账号当前有效，即使已退出空间；不恢复任何资源读取资格 |
| 发起／领取验证 | 当前 model:validate、原责任主体、未撤销凭据、有效模板和政策    |

编辑、验证、分享或空间治理资格不自动授予他人凭据维护权；本批没有责任转交。普通被分享者不能借验证接口使用他人密钥。
凭据重放只返回脱敏历史回执，不重新加密或恢复已撤销值。

首版模板来自当前 FIXED／DATABASE 审核目录，仅支持 chat-completions-sse@v1 且地址以 /chat/completions 结尾。用户选择精确模板，设置
remoteModel、contextWindowTokens 和 maxOutputTokens，不提交任意 URL。 模板去除平台
SecretRef，验证始终使用用户另外提供的个人密钥。返回的模板列表只有引用、供应商及协议，没有 endpoint 或凭据。

租户和空间政策必须同时允许 personalCredentialsAllowed，并分别将协议 ID、providerId、endpoint 主机名列入
allowedProtocols、allowedProviders、allowedDestinations；连接还需符合现有 HTTP origin 白名单。
空名单拒绝。本批不放宽默认政策，也不新增政策管理端点。模板停用或政策收紧后不能继续保存、替换和验证。

## 凭据保存与部署

密钥只在独立凭据表以 SM4-GCM 密文保存；复用 core Sm4Util 的 AAD 重载，使用随机 12 字节 nonce 和 128 位认证标签，AAD
绑定租户、空间、模型、责任主体及凭据版本，防止跨资源或代次替换。 密文格式为 `sm4-gcm:v1:` 加 Base64 (nonce + ciphertext +
tag)，明确算法及封装版本，不按密钥长度猜测算法或自动回退旧 AES。 业务记录持有独立 SecretRef，不把明文或密文写入配置响应、审计、Outbox
或命令回执。幂等使用 HMAC 指纹，不保存普通密钥哈希。 公开凭据视图只有
MISSING／CONFIGURED／REVOKED、版本和本人维护标记；撤销清除当前密文，模型及历史保留。

默认关闭。在 backend/profile/app.properties 设置以下构建开关：

```properties
arte.ai-new-model-management.enabled=true
```

同时需要公共身份、持久化、空间及 AI enabled。通过环境变量／密钥管理系统注入 ARTE_MODEL_CREDENTIAL_KEY，值是 Base64 编码的 16
字节随机 SM4 密钥，默认 keyId=primary。 密钥不要写入仓库、命令行参数或普通日志。显式开启但密钥环无效时启动失败；默认关闭不需要密钥或新表。

可通过外部 Spring 配置设置 `arte.ai-new-model-management.encryption-keys.<id>`、`active-key-id`、`fingerprint-key-id`。
默认环境映射 ARTE_MODEL_ACTIVE_KEY_ID／ARTE_MODEL_FINGERPRINT_KEY_ID 均为 primary。轮换加密密钥时保留旧解密密钥及原
fingerprint-key-id，否则旧密文、幂等请求及游标无法正确核对。本批不提供在线批量重加密。

HMAC-SHA256 指纹算法及输入格式保持不变。SM4 加密密钥必须为 16 字节；显式选作 fingerprint-key-id 的独立密钥还允许保留原来的
32 字节值，用于维持原幂等摘要及游标签名，但不能选作 active-key-id 或用于 SM4 解密。不要截断旧 AES 密钥或用新值覆盖同一
keyId。 如果曾启用旧 AES 实现，现有无格式前缀的 AES
密文不会被新实现解密。切换前须保留原密文和密钥，另行执行经核对的迁移，或由责任主体通过替换凭据接口按当前版本重新录入；当前改造不访问业务库、不自动重写旧密文，也不提供混合算法读取。

新个人凭据仅供验证流程消费，尚未装配到聊天 ConnectionCredentialResolver；4-02 须按当前用途授权和外发门控接入，不能只凭
SecretRef 解密。

## 验证和恢复

配置检查覆盖字段范围、支持协议、模板和政策。网络验证执行认证 GET /models（从模板 /chat/completions 的同路径前缀派生），检查认证及是否列出
remoteModel。 模型列表响应的 data／id 形状参考 [DeepSeek 官方模型列表接口](https://api-docs.deepseek.com/api/list-models/)
，本批用回环服务验证该契约，不调用真实供应商。 不建立聊天 Turn，不调用生成接口、不预留生成预算，不承诺验证了流式、工具调用、上下文或输出容量；字段范围检查不等于供应商能力认证。
供应商对元数据请求的计费不由本系统保证。没有模型列表接口的供应商需另加验证适配器，不能伪报成功。

网络采用精确 origin、地址检查及固定 DNS，禁止重定向和重试；单次网络总上限 15 秒，响应不超过 64 KiB，并发最多 8 且不超过
connectionsPerPool。 远端正文、异常详情和 token 不进入持久化或公开结果。

先同事务登记 PENDING 及草稿版本／凭据版本／模板摘要，再原子领取为 RUNNING，提交后才执行网络请求；数据库锁不跨网络。同键重试不重复已领取探测。
完成及查询重新授权，草稿、凭据或模板变化则 STALE；未完成请求超过 30 秒也变为 STALE。expiresAt 是处理期限，不是已完成结果的供应商能力保证期。
进程故障或浏览器断开后不自动重发；用户查询原 validationId 或以原键确认，必要时显式新建请求。验证成功不发布、不激活空间或取得
model:use。

结果码为 MODEL_FOUND、AUTHENTICATION_FAILED、MODEL_NOT_FOUND、REMOTE_UNAVAILABLE、INVALID_RESPONSE 或
PENDING／RUNNING／STALE。成功只表示本次认证后发现了目标模型。

## HTTP

全部 POST，前缀 /arte/ai-new/model-draft，默认关闭；沿用 Bearer、严格字段、ResultContext、平台错误和 X-Trace-Id。 写命令携带
Idempotency-Key；结果不明确时按原键及原字段确认，不自动生成竞争请求。

| 端点                    | 请求字段                                                                                                        | data                                    |
|-------------------------|-----------------------------------------------------------------------------------------------------------------|-----------------------------------------|
| listModelResources      | scope、cursor 可选、size 可选（默认 20，最大 100）                                                              | records、nextCursor                     |
| listConnectionTemplates | scope                                                                                                           | 模板引用、供应商、协议                  |
| getModelDraft           | scope、resourceId                                                                                               | draft（未保存为 null）、脱敏 credential |
| saveModelDraft          | scope、resourceId、expectedVersion、name、connectionTemplate、remoteModel、contextWindowTokens、maxOutputTokens | CommandReceipt&lt;Draft&gt;             |
| replaceModelCredential  | scope、resourceId、expectedVersion、token                                                                       | CommandReceipt&lt;CredentialView&gt;    |
| revokeModelCredential   | scope、resourceId、expectedVersion                                                                              | CommandReceipt&lt;CredentialView&gt;    |
| validateModelDraft      | scope、resourceId、draftVersion、credentialVersion                                                              | 原受理 receipt、当前验证 current        |
| getModelValidation      | scope、validationId                                                                                             | 当前状态及安全结果                      |

顺序：已有 createModelResource 创建身份 → 选模板 → saveModelDraft → replaceModelCredential →
validateModelDraft／getModelValidation。 receipt 保留原受理证据，current 是独立重检的当前状态，不用完成结果篡改原回执。验证
ID 不能充当访问令牌。

## 验收边界

新增测试覆盖真实 Spring
装配／密钥绑定、MVC／RBAC／Mapper／ACL、普通用户准备、CAS／并发、密文／AAD／轮换／幂等、审计回滚、维护权限分离、退出后撤销、列表游标、验证原子领取／跨空间隔离／模板及凭据变化失效，以及真实回环
HTTP 的认证、响应大小和禁止重定向。 MySQL 使用本次单独创建的一次性 8.4 容器，逐用例创建删除随机库。没有连接业务库或执行其
DDL，没有重启实际节点、调用真实供应商、开放前端或激活旧空间。

2026-10-31 4-01 初始交付验证（AES 实现时的历史基线；SM4 切换验证另列，JDK 21、离线 Maven、显式 Mockito javaagent）：

- 定向回归 262 项：core 68、AI 29、app 165；261 项通过，1 项已有 MySQL 当前读竞争测试在 H2 跳过，无失败／错误。
- 本批新增 22 项全部通过：草稿集成 15、生产装配 3、加密 3、真实回环 HTTP 1。
- 一次性 MySQL 8.4 专项 34 项全部通过：草稿 15、生产装配 3、ACL 执行及竞争 16，包含上述 H2 跳过场景；无跳过。
- 主代码编译、Mapper 注册与独立数据源验证、git diff --check 通过。测试结束后删除本批临时 MySQL 容器。

可在 backend 下以
`mvn -o -pl arte-app -am -Dtest=Sm4GcmTest,Sm4ModelCredentialCipherTest,ModelDraftIntegrationTest,ModelDraftAssemblyIntegrationTest,HttpModelConnectionValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test`
运行当前专项。 受限 JVM 按已有测试配置传入 Mockito javaagent。真实 MySQL 只使用专用隔离实例的
platform.test.mysql，账户约定与既有平台测试一致；用例会创建和删除随机库，不能传业务库。

### SM4-GCM 切换验证（2026-10-31）

生产装配已改用 Sm4ModelCredentialCipher；Sm4Util 增加 AAD 重载，原三参数 GCM 方法保留兼容，认证解密的中间明文缓冲区在成功或失败后清除。
使用 [RFC 8998 附录 A.1 的 SM4-GCM 已知向量](https://www.rfc-editor.org/rfc/rfc8998.html#appendix-A.1)
核对密文及认证标签，并覆盖密钥、nonce、AAD、密文、标签篡改和旧方法兼容。 凭据专项另覆盖随机 nonce、业务范围及代次绑定、密钥轮换、原
32 字节 HMAC 指纹密钥独立保留、旧格式／未知版本／撤销凭据拒绝。

JDK 21 离线 clean test 定向回归共 74 项（core 9、AI 8、app 57），全部通过，无失败、错误或跳过；包含草稿／凭据集成、生产装配、模型管理
HTTP、平台 Mapper 与旧控制面兼容。 清洁构建已移除旧 AES 编译产物。DDL 仅更新算法注释，不改变表结构、索引或约束；未访问或迁移业务数据库。
