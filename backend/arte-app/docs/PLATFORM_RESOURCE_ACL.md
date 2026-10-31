# 公共资源 ACL：第 3 点第 1 批

日期：2026-10-30。

本批提供公共资源 ACL 持久化、统一授权及内部登记／授权管理应用服务。 模型管理资源与精确 bindingRef 的映射、模型发现与执行改接、管理
HTTP 属于后续三批；不改现有 FIXED／DATABASE 来源，不迁移旧资源或账本，不修改文章链路。

## 服务与职责

- `DefaultAuthorizationService` 的 check、require、batchCheck、requireInCurrentTransaction 共用业务资源判定规则。
- `DefaultResourceAccessService` 实现 `ResourceAccessService` 和 `ResourceRegistrationService`；同一个 Bean 暴露两个接口。
- `ResourceGrantStoreServiceImpl` 经平台专用 MyBatis Mapper 访问物理 appDataSource，参与既有事务，不使用旧创建人过滤／路由库。
- app 组合 `ResourceDescriptorProvider`、`DomainAccessConditionProvider`；core 不引用 AI
  类型。每种资源类型及每个资源类型／动作对必须各有一个必要提供者，缺失、冲突或无法读取时拒绝。
- 生产装配没有测试领域适配器；后续第 2 批已增加真实 MODEL
  领域适配和新资源登记，见[模型资源映射](MODEL_RESOURCE_MAPPING.md)。旧模型及文章不会自动获得公共资源授权。

## 范围及判定

首个创建闭环面向空间归属资源，按服务器确认的归属、当前创建资格和领域事实登记。资源必须由当前主体在领域原事务中创建；禁止客户端自报
createdBy 后初始化 ACL。 租户级资源在选定使用空间内的判定及授权记录结构已支持，租户级创建登记仍返回
CAPABILITY_NOT_ENABLED，等待专门租户模型治理批次；本批也不开放跨空间和跨租户使用。

新资源默认 EXPLICIT，创建事务保存显式 CREATION 来源权限和当次成员代次；createdBy、OWNER／ADMIN 角色不动态产生内容权限。
模型预设初始 read、edit、validate、publish、disable、share、use 及 resource:access:manage；实际模型使用仍必须通过后续 AI 领域接入。
已有未接入资源不会因缺少 ACL 而公开。

判定校验当前主体、两级成员、范围、空间状态、公共政策、领域权威归属与 ACL 的一致性、当前动作授权和领域附加条件。 归档空间仅允许已有的资源
read，禁止 use 和 ACL 修改；政策禁止及领域拒绝优先。领域附加条件只能收紧，不能覆盖公共拒绝。

SUBJECT 授权绑定空间成员代次；租户级资源 SUBJECT 授权绑定租户成员代次。移除后重新加入不会恢复旧授权。 角色／空间授权仅在
SPACE_SHARED 模式下匹配当前有效成员；不按角色高低隐式继承。EXPLICIT 不激活角色／空间记录，即使它来自独立管理授权。期限在每次判定时检查。

普通 share 只能授予动作注册表标为 shareDelegable 且授予者当前持有的动作；完整 ACL 管理亦不能授予自己没有的动作。
分享目标种类须同时满足租户和空间政策，不能分享到其他空间。凭据、预算、会话历史不通过模型分享开放。

`SPACE_SHARED` 必须登记明确角色／空间和动作。模式维护的授权使用服务器保留的 `visibility:<operationId>` 来源操作标识；回到
EXPLICIT 或修改共享范围只撤销这组模式授权，不删除直接主体授权或其他独立来源。 撤销保留历史；普通分享者仅能在当前 share
资格下撤销本人 SHARE 来源记录，ACL 管理者可撤销该资源的指定授权；具有 workspace:resource:govern
的治理者可撤销空间资源的异常授权，但不能据此读取私有内容、列出私有 ACL 或给自己增加授权。

## 事务、当前读取与回执

最终写入使用相同排他父租户门控；多主体先按稳定 ID 排序取得主体锁，再取得租户／空间／成员和 ACL。
给其他主体授权时，调用方外层事务也必须遵守这个顺序；不能持有租户后再新增主体锁。违反共享顺序会拒绝并将实际事务标记回滚。

SPI 增加必实现的 `ResourceDescriptorProvider.findCurrent`、`DomainAccessConditionProvider.evaluateCurrent`、
`ResourceGrantStore.findCurrentGrants/findCurrentGrant`。 当前方法必须在调用方原写事务读取最新事实，不能默认委托普通读取；展示读取不取写锁。授权根通过
lockAccess 当前读。 授权页当前计数用锁定主键集合，避免 MySQL REPEATABLE READ 下普通 COUNT
的旧快照。写回调前的范围事实只在本次批次内复用，进入回调后不缓存允许结论。

资源创建、初始授权、范围 epoch、审计、Outbox、命令回执与领域创建同库、同线程原子提交。登记服务必须在已有可写事务内调用，不能自行提交半个资源。
授予、撤销、模式更新分别校验预期 ACL／grant 版本，推进 ACL 版本及所属空间／租户 epoch。撤销只允许 ACTIVE →
REVOKED，不能改目标、来源、期限或复活记录。

请求摘要规范化动作集合及共享列表顺序。同键重放重检当前资格，不增加授权、审计或事件；同键不同内容拒绝。 新增结果 schema：
`resource:access:v1`、`resource:grant:v1`、`resource:void:v1`。Void 明确保存 null；固定 kind 白名单编码主体／角色／空间，不使用
Java 类名多态。 本批通过应用服务返回和原命令重试取得回执；既有空间 HTTP 回执查询尚不开放这些新 schema。

## 数据库与部署

迁移文件：[arte-platform-resource-acl-ddl-mysql.sql](../scripts/arte-platform-resource-acl-ddl-mysql.sql)。在身份、空间基础
DDL 之后，通过正式迁移执行一次；启动不执行 DDL，也不自动回填任何模型。

两张表：`arte_platform_resource_access` 保存 ACL 根，`arte_platform_resource_grant` 保存独立授权。
固定长度资源／授权来源摘要承担物理唯一索引，原租户、资源类型、ID、归属和完整授权保留在受检列及封闭 JSON
中；读取核对原身份与摘要，不能用摘要作授权身份。 领域元数据仍为归属及资源版本权威，ACL 镜像不能覆盖领域记录。

单次授权最多扫描 10,000 条含撤销历史的记录，每页最多 100 条。超限明确拒绝为 AUTHORITY_UNAVAILABLE，不截断后放行或误报没有授权。
管理列表也遵守此上限。大规模授权及历史归档需要后续候选索引／定向查询设计；本批不宣称高容量性能验收。

## 验证

`ResourceAclIntegrationTest` 使用真实平台 Mapper、DDL、事务及测试领域表，生产不注册该领域适配。
覆盖私有默认、初始登记重放、独立来源、直接／角色共享、TTL、成员代次、普通分享防越权、版本 CAS 竞争、撤权与最终提交竞争、旧领域快照、原事务回滚及
JSON 回执。 测试通过 `platform.acl.test.mysql` 可连接专用 MySQL 测试实例，逐用例创建并删除随机库；不得传业务或生产实例。

最终定向回归共 328 项全部通过：core 空间／平台及包边界 185 项，AI 控制面 33 项，app 装配／持久化／空间 HTTP／控制面／ACL 110 项。
其中新增 ACL 专项 26 项在 H2 与专用隔离 MySQL 8.4 各通过一次，均无失败、错误或跳过。MySQL 专项覆盖实际外键、REPEATABLE READ
旧快照、CAS 并发与撤权竞争。 临时 MySQL 随机库及容器已清理。测试需要 JDK 21；本机 JDK 24 默认注解处理及沙箱内 Mockito
自附加失败，最终回归使用 JDK 21 和显式 Mockito javaagent。 本批未执行业务库迁移、重启服务、真实模型调用、浏览器、多节点或容量压测验收。

复现 H2 专项（在 backend 下、JDK 21）：

```shell
mvn -o -pl arte-app -am -Dtest=ResourceAclIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

MySQL 专项在同一命令中额外传入专用测试实例的
`-Dplatform.acl.test.mysql=jdbc:mysql://127.0.0.1:<隔离端口>/mysql?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true`，
整个参数应加单引号避免 shell 解释连接参数。仅使用测试用户名 root／测试密码 platform-test 的隔离实例。

第 2 批已提供稳定模型身份和版本映射，见[模型资源映射](MODEL_RESOURCE_MAPPING.md)；第 1 批历史验收范围与计数保持原记录。

## 公共 ACL 第三批实施记录

第三批已将模型作为首个接入资源，通过显式空间迁移改接统一资格、受理、派发和外发前复核。当前边界、部署和验证见[模型 ACL 发现与执行](MODEL_ACL_EXECUTION.md)。
