# 政策治理与个人租户模板

`WorkspacePolicyService` 由 `DefaultWorkspacePolicyService` 实现，并装配为 `workspacePolicyService`。HTTP 经
`ReactiveWorkspaceOperations` 在既有数据库线程执行可信身份核验和完整同步服务调用。

## 治理 HTTP

以下接口均为 POST，默认上下文 `/arte`，受 `arte.platform.workspace.enabled` 开关控制。身份来自认证会话。

| 路径                                    | 请求体                                                                                     | `ResultContext.data`                                                           |
|-----------------------------------------|--------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------|
| `/arte/workspace/getTenantPolicy`       | `tenantId`                                                                                 | `{policy, tenantVersion, tenantEpoch}`                                         |
| `/arte/workspace/getWorkspacePolicy`    | `scope`                                                                                    | `{policy, workspaceVersion, workspaceEpoch, tenantPolicyVersion, tenantEpoch}` |
| `/arte/workspace/updateTenantPolicy`    | `tenantId, expectedTenantVersion, expectedPolicyVersion, rules, membersMayCreateWorkspace` | `CommandReceipt<TenantPolicy>`                                                 |
| `/arte/workspace/updateWorkspacePolicy` | `scope, expectedWorkspaceVersion, expectedPolicyVersion, rules`                            | `CommandReceipt<WorkspacePolicy>`                                              |

两个更新接口必须携带 `Idempotency-Key`。`rules` 为完整 `PolicyRules` 目标值；缺失集合不是保留原值，空集合表示禁止。
治理端先查询政策视图，用同次读取返回的父版本和 `policy.version` 构造更新。首次成功更新使政策版本、父记录版本及父权限
`authEpoch` 各加一；回执中的 `resultVersion` 是政策版本。父记录版本改变后应重新查询视图。

查询和更新均要求对应政策写资格：租户 OWNER／ADMIN；空间 OWNER。租户角色不隐式获得空间治理资格。 身份、两级成员、租户／空间生命周期、Schema
和角色预设仍沿用公共授权检查；归档空间不开放政策治理。 普通空间详情／目录不返回完整政策。客户端不能修改
Schema、角色预设版本、权限代次或模板来源版本。

命令先按已有顺序取得主体、父租户、空间及成员治理锁，然后当前读重新授权。两个预期版本任何一个不匹配均返回
`VERSION_CONFLICT`（409）。政策 CAS、父记录 CAS、权限代次、Outbox、审计及命令回执在原物理库事务一起提交，失败整体回滚。
同键同内容重放保留原操作 ID、结果版本和时间，重新核验当前治理资格，不重复写入或审计；同键不同内容返回
`IDEMPOTENCY_CONFLICT`（409）。请求摘要按集合和 Map 的稳定顺序生成。既有 `/getWorkspaceCommandReceipt` 支持两种政策
回执，并按当前政策治理资格保护完整历史结果；降级为普通成员后不能读取原治理回执。

空间政策只能收紧租户上限：创建类型、额外创建角色、访客 AI、个人凭据、协议／供应商／目的地白名单、导出／外发类型、
分享目标类型均被检查，超出上限返回 `POLICY_DENIED`（403）。租户收紧时不批量重写空间政策；实际授权继续同时受两级
政策约束，空间的下一次治理更新须符合当时租户上限。新增空间从已保存租户政策创建，沿用原规则。

## 数据库模板与迁移

生产装配不再使用 `arte.platform.workspace.personal-policy.*`。这些旧配置必须移除，否则严格配置绑定会拒绝启动。
`workspace.enabled` 保留。代码中的保守模板辅助工厂仅供独立装配／测试使用，生产 Spring 装配始终使用数据库 SPI。

新安装依次执行身份 DDL、空间 DDL、
[
`arte-platform-personal-policy-template-ddl-mysql.sql`](../scripts/arte-platform-personal-policy-template-ddl-mysql.sql)。
已有空间表的安装先执行
[`arte-platform-policy-provenance-migration-mysql.sql`](../scripts/arte-platform-policy-provenance-migration-mysql.sql)
，再执行模板 DDL。 既有租户的 `personal_template_version` 保持 NULL，不推断来源，不修改规则、政策版本或权限代次。 模板 DDL 的
v1 为保守默认值；若原部署自定义了个人默认政策，应在首次应用前将种子规则调整为已审核的部署值。 应用启动、构建和初始化均不自动执行
DDL。

`arte_platform_personal_policy_template` 保存修订内容、Schema／角色预设、版本及部署发布人／时间；
`arte_platform_personal_policy_template_head` 保存唯一发布指针。每次新个人租户创建在初始化原事务内锁定当前发布指针并读取
对应修订，复制为独立租户政策；租户政策版本从 0 开始，`personalTemplateVersion` 保存模板来源，后续治理更新保留此来源。
初始化事件也记录模板版本。模板缺失、规则不能解析或 Schema／角色预设不支持时返回 `AUTHORITY_UNAVAILABLE` 并回滚。
已有个人租户初始化和命令重放无需读取模板；多实例共同读取数据库，重启不会隐式改变政策。

## 发布新修订

模板由部署迁移账号发布，应用不开放模板修改 HTTP，不把租户治理资格扩展为全平台模板发布权限。应用账号只需模板表的读取
权限；部署账号按已审核迁移追加修订、移动指针，禁止覆写或删除历史修订。发布流程如下（`:rules` 等为迁移工具绑定参数）：

```sql
START TRANSACTION;
SELECT version
FROM arte_platform_personal_policy_template_head
WHERE id = 1 FOR UPDATE;
-- 校验读到的版本等于 :expectedVersion；否则 ROLLBACK 并报版本冲突。
-- :newVersion 必须等于 :expectedVersion + 1；Schema 和角色预设当前均为 1。
INSERT INTO arte_platform_personal_policy_template
(version, rules, members_may_create_workspace, policy_schema_version, role_preset_version, published_by, published_at)
VALUES (:newVersion, :rules, :membersMayCreateWorkspace, 1, 1, :deploymentActor, CURRENT_TIMESTAMP);
UPDATE arte_platform_personal_policy_template_head
SET version=:newVersion
WHERE id = 1
  AND version = :expectedVersion;
-- UPDATE 影响行数必须为 1，否则 ROLLBACK；迁移工具在同一连接内检查后才提交。
COMMIT;
```

先使用 `PlatformJson`／`PolicyRules` 校验修订 JSON，集合使用固定类型，`MyEnum` JSON 为数值（分享目标 SUBJECT=0、SPACE_ROLE=1、
WORKSPACE=2）。发布指针更新与新租户初始化互斥：初始化取得并持有某一版本，发布提交后开始读取的新租户采用新修订。
发布只涉及两张模板表，不更新既有租户／空间政策，也不改变其授权代次。需改变既有政策时调用上述治理 HTTP。
回退模板规则也须追加一个新版本，保留完整发布历史。

## 验证

单元测试覆盖治理资格、两种版本冲突、全部政策上限字段、幂等、当前读、回执查询及事务失败回滚。 HTTP 与真实 MyBatis／H2
集成覆盖政策读写、来源版本、审计和权限代次、缺失参数／异常规则、越权和跨租户拒绝。 多实例／Spring
装配测试覆盖模板发布后仅新租户使用新规则，以及模板缺失或未知版本时初始化失败且原租户不受影响。 MySQL 脚本遵循既有部署迁移方式，测试默认连接隔离
H2 库。

本次 core／H2 集成／HTTP 安全与会话回归共 272 项通过，无失败或错误；4 项 Redis 集成测试因未配置测试 Redis 而跳过。未在业务数据库执行迁移。
