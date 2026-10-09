# 平台公共基础契约

本轮完成实施第 1 步：领域值对象、枚举、命令／View、公共 API 和环境 SPI。这里只提供基础契约及结构校验，默认应用服务、权限计算、数据库、HTTP
与 `ainew` 接入在后续阶段实现。接口没有空服务实现，也没有注册可用管理端点。

## 包位置

| 内容                                     | 包                                                  |
|------------------------------------------|-----------------------------------------------------|
| 主体、命令、错误等枚举                   | `com.arte.core.enums.platform`                      |
| 租户、空间、成员、邀请等枚举             | `com.arte.core.enums.workspace`                     |
| 归属、访问模式、授权与判断枚举           | `com.arte.core.enums.access`                        |
| 稳定身份、范围、回执、分页、事件等值对象 | `com.arte.core.pojo.platform`                       |
| 租户、空间、成员、政策、偏好、关闭等对象 | `com.arte.core.pojo.workspace`                      |
| 资源事实、ACL、授权及判断对象            | `com.arte.core.pojo.access`                         |
| 业务消费者调用的接口                     | `com.arte.core.api.platform`、`workspace`、`access` |
| 环境与领域实现的端口                     | `com.arte.core.spi.platform`、`workspace`、`access` |
| 类型化应用错误                           | `com.arte.core.exception.platform`                  |
| 资源类型及动作名称                       | `com.arte.core.constant.platform`                   |
| 结构校验辅助                             | `com.arte.core.utils.platform`                      |

枚举沿用 `StatusEnum` 的数值码、中文描述、`IEnum<Integer>`／`MyEnum<Integer>` 及读取／写入转换器，并提供
`@JsonCreator fromValue`。数值码固定，不能按枚举顺序推算或升级时重排。错误对外如需文档中的字符串代码，由 app 映射枚举
`name()`，HTTP Schema 尚未发布。

领域和命令使用不可变 record；集合防御复制，政策中嵌套的角色集合也深复制。领域对象不带 ORM 注解，不继承依赖 MyBatis 的
`PageView`；HTTP 分页适配可从现有 `PageParam` 转为 `PageQuery`，结果由 app 包装。主体、归属和权限不是同一个对象。

## API 使用边界

- `PersonalWorkspaceService`、`WorkspaceService`、`ScopeResolver`：个人初始化、空间管理及当前范围验证。个人初始化后续实现只接受有效
  USER，不给 SERVICE 建默认个人空间。
- `MembershipService`、`TenantService`：空间邀请／成员治理及租户治理。组织创建另受 `TenantProvisioningPolicy` 限制。
- `WorkspacePreferenceService`、`WorkspacePolicyService`：本人偏好及获授权治理政策。默认空间关联不能通过偏好更新修改；完整政策只供相应治理资格使用，不混入普通空间查询
  View。
- `AuthorizationService`：唯一通用动作判定入口；`check`、`require`、`batchCheck` 使用同一规则，最多 100 项批量输入。领域必要条件通过
  `DomainAccessConditionProvider` 收紧，不能覆盖公共拒绝。
- `ResourceAccessService`：授权列表、授予、撤销及可见模式更新，内部仍调用统一判定器。
- `ResourceRegistrationService`：受信领域创建事务登记 ACL 及初始授权，不能开放为客户端自报归属的初始化 HTTP。
- `WorkspaceLifecycleService`：归档、取消归档、关闭预检／受理／进度。关闭参与者未实现并装配前不开放关闭承诺；首版无取消关闭或软删除恢复接口。
- `CommandReceiptQueryService`：原操作主体按当前可见资格读取回执，不能读取存储信封或恢复已撤销授权。

`WorkspaceCommands`、`TenantCommands`、`ResourceAccessCommands` 按接口族组织写命令；`WorkspaceViews`、`ResourceAccessViews`
组织不可变返回对象。原设计中的伪代码类型按上述嵌套类型落地。多动作 grant 返回 `GrantResult`，含逐项记录及 ACL
版本；授权管理列表明确传入当前 scope。

## access 管理关系与接口覆盖

`com.arte.core.spi.access`
的五个接口已覆盖顶层设计首版所需的协作职责；当前只是契约，权限规则、事务门控和持久化实现仍待完成。接口关系图及首次授权变更时序图见[顶层需求及设计](../../ARTE%20顶层需求及设计.md)
的“详细设计 → 2.4 授权管理与统一判断入口”。

| SPI                             | 现有方法                                                                                                                      | 职责                                                             |
|---------------------------------|-------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| `ActionRegistry`                | `find`                                                                                                                        | 查询受信动作定义，未知动作拒绝                                   |
| `ResourceDescriptorProvider`    | `supports`、`find`                                                                                                            | 领域原始可信归属、状态和版本，不返回正文或递归调用公开查询       |
| `DomainAccessConditionProvider` | `supports`、`evaluate`                                                                                                        | 领域附加条件，只能收紧公共判定                                   |
| `ResourceGrantStore`            | `findAccess`、`lockAccess`、`insertAccess`、`replaceAccess`、`findGrants`、`findGrant`、`insertGrantIfAbsent`、`replaceGrant` | ACL 根与授权记录的查询、锁定及条件写入，保留撤销历史，不计算权限 |
| `WorkspaceWriteGuard`           | `inCurrentTransaction`                                                                                                        | 调用方真实事务内锁定门控，读取当前事实，经统一判定后执行写入回调 |

业务模块通过 `api.access` 的统一判定、授权管理和受信初始登记服务使用这些能力。主体、成员、政策及 epoch 由 `spi.workspace`／
`spi.platform` 协作；事务、回执、审计与 Outbox 不另放一套在 access 包中。公共 ACL 管访问规则，领域管理业务数据及真实归属。

新资源登记先在既有范围验证创建资格，再与正文同事务登记初始 ACL；已有资源的授权变更在门控内重查资格并条件写入 ACL 与对应范围
epoch。提供者缺失或冲突、必要条件无法核对、CAS 失败及基础设施故障都不能静默放行。详细流程是下一步默认应用服务与 app
适配器的实现约束，不是本轮已经提供的运行功能。

## 必须保留的规则

1. `Principal.subjectId` 为稳定身份，用户名不入映射键。`PrincipalIdentityStore` 保留注销关联且禁止重绑，已有 AI 用户接入时核对原
   subjectId。
2. `Scope` 只是选择。`TrustedActor` 仅由可信认证适配创建；`VerifiedScope`／`VerifiedAccess` 是检查快照，不是 bearer
   token。它们不能直接绑定 HTTP 请求后当作可信对象，实际写入仍重新授权。
3. `ResourceOwnership` 只允许 TENANT／WORKSPACE，createdBy 不产生永久权限。空间负责人从有效 OWNER
   成员推导，资源与空间不保存第二个可漂移的负责人字段。
4. 租户／空间角色为不同枚举，不隐式继承。OWNER 交接为独立命令；普通改角色不能授予 OWNER。
5. `GrantRecipient` 是写命令中的期望目标，不带成员代次。服务端验证当前资格后构建 `GrantTarget`
   ；具体主体授权绑定当次成员代次，重新加入不复活旧直接授权。
6. Grant 只表达 ALLOW，一记录一动作且各来源独立；禁止来自主体、成员、政策及领域状态。SPACE_SHARED 必须有明确空间／角色动作，不能自动生成完全控制。
7. `Action` 只检查命名格式，`PlatformActions` 只提供名称。未知动作必须由受信注册表拒绝，不能因构造成功、角色名称或前端显示而放行。
8. 领域提供者返回原始可信归属和状态，不调用已经受保护的业务读取 API，避免权限判断递归。资源正文、模型连接、凭据和预算不放进公共
   ACL。

## SPI 的事务与失败契约

- 所有公共 API／存储 SPI 为同步调用，公共模型不含 Reactor。阻塞调用的 MVC／响应式线程适配由 app 负责。
- `PublicTransactionRunner.required` 加入同线程同 DataSource 当前事务；事务内不切线程，不等待供应商或远端服务。
- 个人初始化、邀请接受、资源与初始 ACL、负责人交接、授权与 epoch、回执／审计／Outbox 必须按对应操作原子提交。唯一约束及 CAS
  的数据库实现由 app 负责。
- `WorkspaceWriteGuard` 只在调用方实际提交事务内执行。治理门控按租户、排序后的空间、成员／ACL 同序锁定；不能先在另一个事务取永久许可再保存。
  `PublicTransactionRunner.requireActive` 可供实现拒绝事务外协作。
- Store 的 `insert`／`insertIfAbsent` 为唯一条件写入；`replace` 为 expectedVersion 条件更新，成功时按规则递增版本。false
  表示唯一／版本条件未满足，不能静默覆盖。基础设施不可用必须报告失败，不伪装成未找到或允许。
- `WorkspaceStore` 提供有界成员候选扫描，候选不是最终授权。公共服务通过统一判断计算可见分页及 total；`ResourceGrantStore`
  也使用有界分页扫描。
- 幂等范围由可信 actor、CommandScope、commandType、operationKey 组成。初始化／租户命令的空空间在数据库唯一键中编码明确范围；摘要保存原始身份并核对碰撞。
- `StoredCommandReceipt` 的 resultSchema 由受信注册表解析。摘要、回执、事件和审计不存凭据明文或未授权正文，适配器不得按客户端类名创建对象。
- `CleanupAuthorizationVerifier` 在实际领域收尾事务验证 SERVICE、工作项、固定目标、动作、版本及有效期；AI 的原执行 owner／账本仍由
  AI 保留，公共端口不引用 AI 类型。
- 关闭工作项 WAITING_EVIDENCE／FAILED 或缺少必要参与者时，不能进入删除完成状态；不得把空实现当作 DONE。

## 验证及下一步

新增测试覆盖归属范围、命令边界、共享明确动作、成员代次结构、不可变集合、有效收尾快照、回执状态、分页边界、枚举 JSON／转换器及模块依赖。

在 `backend` 下执行：

```shell
mvn -o -pl arte-core -am \
  -Dtest=PlatformContractTest,PlatformEnumSerializationTest,PlatformPackageBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

下一步在 `com.arte.core.application.workspace`／`com.arte.core.application.access` 实现个人初始化、基础空间管理及统一授权规则，并使用测试
Store 验证。随后再由 app 实现 `arte_platform_` 表及相关身份、事务、HTTP 适配；本轮不切换既有 `ainew` 的固定授权路径。
