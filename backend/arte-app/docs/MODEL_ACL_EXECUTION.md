# 模型发现与执行接入公共 ACL：第 3 点第 3 批

日期：2026-10-31。

本批在稳定模型资源／精确版本映射上，把 `model:use` 接入生产 `AiResourceEntitlementService`，覆盖模型发现、精确绑定／能力／连接解析、普通聊天与
Tool Chat 的模型使用，以及现有受理、派发、外发前门控。文章、管理 HTTP、凭据用途治理和预算规则保持原实施范围。

## 显式选择迁移范围

新增 `arte_ai_model_acl_scope`，按完整租户和空间保存迁移清单。没有精确记录时沿用旧 Grant／空间配置语义；有记录时，只有清单内的精确
bindingRef 可以进入公共 ACL 判定。空集合表示全部拒绝；不提供删除清单或退回 LEGACY 的应用
API。缺表、读取故障、坏数据、映射缺失、同引用不同内容不能解释为未迁移。

配置模式与资格来源分别处理：清单固定该空间唯一的运行配置来源 FIXED 或
DATABASE；来源不匹配直接拒绝。清单不能用于切换配置来源。每个模型版本仍保留最初登记的定义来源；DATABASE 目录可以引用与原
FIXED 发布完全相同的已导入定义，不能通过副本改写其权威来源或内容。

本批只接受模型主空间作为执行空间。不同空间不能借相同 bindingRef 取得对方模型；跨空间复用需要后续明确协议。

## 内部迁移应用服务

`ModelAclMigrationService.migrate(actor, Migrate)` 是同步数据库线程上的内部命令：

- 首次 `expectedVersion=0`；后续携带当前清单版本，CAS 每次加一。
- 清单包含明确的配置来源及最多 256 个精确 bindingRef，不接受 latest、模型 ID 族或客户端定义快照。
- 同时检查当前 `workspace:policy:write` 和当前 RBAC `aiConfiguration:manage`／`admin`；拥有模型创建或发布权不能自行切换整个空间。
- 逐项核对同空间的 PUBLISHED 管理资源、不可变精确映射及受信运行定义；DATABASE 还要求空间 AI 配置已启用并包含所有清单引用。
- 核对当前实际资格服务已装配同一个公共 ACL 门控，且执行使用 `appDataSource`。自定义旧资格服务不能在运行期间启用迁移。
- 迁移行、空间版本／authEpoch、审计、Outbox 和封闭 schema `model:acl:scope:v1` 幂等回执在原物理事务中提交。重放重新检查当前管理资格，不重写清单。

调用形式如下；身份和范围必须来自宿主可信管理边界，不从 HTTP 请求体构造 TrustedActor：

```java
migration.migrate(actor, new ModelAclMigrationService.Migrate(
        new CommandMeta(operationKey, traceId), scope, AiEntitlement.Source.DATABASE,
        Set.of(exactBindingRef), 0));
```

这是内部应用 API，本批没有开放迁移或授权管理 HTTP。

## 统一判定和提交边界

`ConfigurationAiResourceEntitlementService` 在解释旧 Grant 绑定列表前调用 `ModelAclAccess`。未迁移范围保持兼容；已迁移范围只使用公共允许结果，ACL
拒绝不能与旧 Grant 合并。精确版本与连接、能力、模型标识、费率内容必须匹配第二批保存的不可变发布内容。

`PlatformModelAclAccess` 组合原始 ModelResourceStore 与公共 AuthorizationService，不反调模型管理应用
API。展示检查使用独立只读快照，批量模型授权每批最多 100 项；单次候选最多 256 项。当前身份、空间 AI 使用资格、成员／政策／生命周期、模型
ACL 和模型 PUBLISHED 状态共同决定资格。

`AiEntitlement.modelAccess` 保存本次资源、归属、公共授权版本、清单版本和原定义来源，仅为内存依据。它不进入 HTTP、Invocation
持久化、请求摘要或账本，不代替下一次授权，也不改变 ExecutionOwner、创建者或凭据责任主体。

- 受理／派发预检调用同一资格端口；原写事务中的 Permit 再调用 `requireInCurrentTransaction`。
- 当前读取先按既有顺序取得主体及父租户／空间门控，再读取当前迁移清单、精确映射、模型和公共授权。与迁移、撤权、停用共用父锁，锁持续到原事务结束。
- 不读取 Reactor 展示快照，不另开事务冒充最终写入。预检后从 LEGACY 切到 ACL 会使原许可失效，需要重新预检。
- 允许的版本发生推进时仍检查当前实际权限；版本相等也不会跳过授权。
- 派发准备和发送事实记录继续使用已有原子门控。真正模型发送前的 `ConnectionManager.resolveCurrent(GatewayCall)` 重新执行
  EXTERNAL_SEND 检查；早先的发现或发送预检结果不能充当永久许可。

数据库锁不跨模型网络请求。撤权在最终数据库门控之前提交会阻止本次写入；执行先持锁时，撤权等待原事务结束。外发前最后一次检查之后已经发出的请求不能被数据库撤权追溯取消，继续遵守现有发送事实和
UNKNOWN 处理。

模型 ACL 不授予预算、提高额度或提供密钥。原 ai:invoke 上限、截止时间、DATABASE 空间 AI
预设、预算归属／费率、余额预留、部署政策、连接及凭据解析仍独立生效。FIXED 迁移只替换模型绑定 ID 资格判断，不取消粗粒度操作授权和预算配置要求。

## 部署次序

1. 先按原说明部署公共身份／空间、公共 ACL 和模型资源三表。
2. 执行 [arte-ai-model-acl-scope-ddl-mysql.sql](../scripts/arte-ai-model-acl-scope-ddl-mysql.sql)
   ，新增第四张模型相关表。脚本无旧模型回填、无自动切换。
3. 升级全部 API／Worker 节点并排空旧版本，再用第二批内部服务明确登记管理资源和精确版本。
4. 确认模型、空间配置、预算和凭据可用后，通过上述内部命令逐空间激活。

已迁移后不能回滚到不含本批功能的旧二进制，也不能删除清单伪造未迁移。当前版本的 `ModelAclStartupSafety`
在发现已有清单时拒绝关闭公共门控、安装旧资格服务或使用其他执行数据源；它不能约束尚未升级的旧节点，因此激活前必须完成全节点升级。当前启动检查不执行
DDL、不回填，也不向数据库插入迁移记录。

## 验证

最终定向回归共 372 项：core 平台／空间与公共契约 185 项、AI 控制面 33 项、app 装配／持久化／HTTP／ACL 与模型专项 154 项；371
项通过，1 项 MySQL 当前读专项在 H2 跳过，无失败或错误。新增专项共 24 项在一次性隔离 MySQL 8.4 全部通过，包含 H2 跳过的插入迁移行竞争。
`git diff --check` 通过。使用 JDK 21 和显式 Mockito javaagent。

`ModelAclExecutionIntegrationTest` 使用真实 DDL、Mapper、RBAC、公共 ACL、迁移和模型资格；`ModelAclRuntimeIntegrationTest`
使用生产 Spring 装配、真实聊天受理及原子派发存储，禁止真实供应商网络调用。

专项覆盖未迁移兼容、精确清单、私有／共享／关闭共享、撤权与停用、旧 Grant
不兜底、预算独立、原执行归属、不同空间隔离、原定义来源保留、CAS／幂等／审计／epoch、缺表／坏映射／变更连接、原事务要求、旧快照与并发撤权、受理回滚、派发无
Attempt／预留及外发前重检。启动和激活测试另覆盖旧资格服务绕过防护。

H2 的 REPEATABLE READ 允许此处“快照建立后插入迁移行”的幻读，且 FOR UPDATE 不能模拟 MySQL 当前读，因此该项仅在 MySQL 验证；H2
的更新竞争可能直接中止事务。MySQL 专项要求真实当前读取阻止旧许可，不能用 H2 结果代替。

使用 JDK 21，在 backend 下运行：

```shell
mvn -o -pl arte-app -am -Dtest=ModelAclExecutionIntegrationTest,ModelAclRuntimeIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
```

Mockito 在限制动态 attach 的环境需显式 javaagent。MySQL 验收只传专用隔离实例的 `platform.test.mysql`
，root／platform-test，URL 库名 `/mysql?`；逐用例创建／删除随机测试库，不能传业务库。验证不包含浏览器、多节点并发、容量压测或真实模型请求；没有执行业务库
DDL 或切换现有空间。
