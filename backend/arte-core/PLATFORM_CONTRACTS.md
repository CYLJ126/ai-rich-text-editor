# 平台公共基础契约

已完成实施第 1～2 步：基础契约，以及个人初始化、基础空间管理、本人偏好、范围验证和基础动作授权的默认实现。默认服务通过 SPI
执行，不注册生产内存存储或管理 HTTP 端点。数据库、登录身份适配、资源 ACL 管理、成员治理、生命周期及 `ainew` 接入继续分步实施。

## 包位置

| 内容                                     | 包                                                          |
|------------------------------------------|-------------------------------------------------------------|
| 主体、命令、错误等枚举                   | `com.arte.core.enums.platform`                              |
| 租户、空间、成员、邀请等枚举             | `com.arte.core.enums.workspace`                             |
| 归属、访问模式、授权与判断枚举           | `com.arte.core.enums.access`                                |
| 稳定身份、范围、回执、分页、事件等值对象 | `com.arte.core.pojo.platform`                               |
| 租户、空间、成员、政策、偏好、关闭等对象 | `com.arte.core.pojo.workspace`                              |
| 资源事实、ACL、授权及判断对象            | `com.arte.core.pojo.access`                                 |
| 业务消费者调用的接口                     | `com.arte.core.api.platform`、`workspace`、`access`         |
| 环境与领域实现的端口                     | `com.arte.core.spi.platform`、`workspace`、`access`         |
| 类型化应用错误                           | `com.arte.core.exception.platform`                          |
| 资源类型及动作名称                       | `com.arte.core.constant.platform`                           |
| 默认应用服务、基础授权及事务协作         | `com.arte.core.application.platform`、`workspace`、`access` |
| 结构校验辅助                             | `com.arte.core.utils.platform`                              |

枚举沿用 `StatusEnum` 的数值码、中文描述、`IEnum<Integer>`／`MyEnum<Integer>` 及读取／写入转换器，并提供
`@JsonCreator fromValue`。数值码固定，不能按枚举顺序推算或升级时重排。错误对外如需文档中的字符串代码，由 app 映射枚举
`name()`，HTTP Schema 尚未发布。

领域和命令使用不可变 record；集合防御复制，政策中嵌套的角色集合也深复制。领域对象不带 ORM 注解，不继承依赖 MyBatis 的
`PageView`；HTTP 分页适配可从现有 `PageParam` 转为 `PageQuery`，结果由 app 包装。主体、归属和权限不是同一个对象。

## API 使用边界

- `PersonalWorkspaceService`、`WorkspaceService`、`ScopeResolver`：个人初始化、空间管理及当前范围验证。个人初始化默认实现只接受有效
  USER，不给 SERVICE 建默认个人空间。
- `MembershipService`、`TenantService`：空间邀请／成员治理及租户治理。组织创建另受 `TenantProvisioningPolicy` 限制。
- `WorkspacePreferenceService`、`WorkspacePolicyService`：本人偏好及获授权治理政策。默认空间关联不能通过偏好更新修改；完整政策只供相应治理资格使用，不混入普通空间查询
  View。
- `AuthorizationService`：唯一通用动作判定入口；`check`、`require`、`batchCheck`、`requireInCurrentTransaction` 使用同一规则，最多
  100 项批量输入。领域必要条件通过
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
的五个接口已覆盖顶层设计首版所需的协作职责；第二步已实现基础角色判定及租户／空间事务门控，业务资源 ACL
判定、领域条件和持久化仍在后续阶段接入。接口关系图及首次授权变更时序图见[顶层需求及设计](../../ARTE%20顶层需求及设计.md)
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
epoch。提供者缺失或冲突、必要条件无法核对、CAS 失败及基础设施故障都不能静默放行。业务资源 ACL 的详细流程仍是后续授权管理及
app 适配器的实现约束；第二步基础授权不会为未接入的正文、模型配置或凭据放行。

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
- `PublicTransactionRunner.resource(key, factory)` 将协作状态绑定到实际外层事务；嵌套 required 和不同服务实例共享，
  事务挂起／恢复时跟随原事务，提交／回滚后清理。`markRollbackOnly()` 必须标记原事务，异常被捕获也不能提交。 两个方法均需由 app
  真实实现，没有每次新建状态或仅按线程缓存的默认回退。
- 个人初始化、邀请接受、资源与初始 ACL、负责人交接、授权与 epoch、回执／审计／Outbox 必须按对应操作原子提交。唯一约束及 CAS
  的数据库实现由 app 负责。
- `WorkspaceWriteGuard` 只在调用方实际提交事务内执行。治理门控按租户、排序后的空间、成员／ACL 同序锁定；不能先在另一个事务取永久许可再保存。
  `PublicTransactionRunner.requireActive` 可供实现拒绝事务外协作。
- Store 的 `insert`／`insertIfAbsent` 为唯一条件写入；`replace` 为 expectedVersion 条件更新，成功时按规则递增版本。false
  表示唯一／版本条件未满足，不能静默覆盖。基础设施不可用必须报告失败，不伪装成未找到或允许。
- `PreferenceStore.lockSubject` 在实际事务中锁定主体门控，即使偏好尚不存在也须覆盖初始化竞争；app
  可锁稳定身份行。初始化和偏好更新先锁主体，再按租户、空间、成员顺序锁治理范围，不使用 JVM 锁代替数据库并发约束。
- `WorkspaceStore` 提供有界成员候选扫描，候选不是最终授权。公共服务通过统一判断计算可见分页及 total；`ResourceGrantStore`
  也使用有界分页扫描。
- 幂等范围由可信 actor、CommandScope、commandType、operationKey 组成。初始化／租户命令的空空间在数据库唯一键中编码明确范围；摘要保存原始身份并核对碰撞。
- `StoredCommandReceipt` 的 resultSchema 由受信注册表解析。摘要、回执、事件和审计不存凭据明文或未授权正文，适配器不得按客户端类名创建对象。
- `DefaultCommandExecutor` 首次执行与重放采用同一结果类型规则：`Void.class` 仅接受 `value=null`，其他结果类型要求非空且可赋值。
  Void 重放仍校验命令身份、摘要、resultSchema、APPLIED 状态及当前权限；`authorizeReplay` 接收 null，须按命令中确定的目标重检权限。
  首次结果不符合类型契约时拒绝保存回执并回滚领域写入；重放保留原操作 ID、结果版本及时间，不重复执行领域回调。
- `CleanupAuthorizationVerifier` 在实际领域收尾事务验证 SERVICE、工作项、固定目标、动作、版本及有效期；AI 的原执行 owner／账本仍由
  AI 保留，公共端口不引用 AI 类型。
- 关闭工作项 WAITING_EVIDENCE／FAILED 或缺少必要参与者时，不能进入删除完成状态；不得把空实现当作 DONE。

## 最终授权的当前读取

普通 `find` 和 `snapshot` 用于展示查询，不能证明外层事务中的当前写入资格。
`AuthorizationService.requireInCurrentTransaction` 和默认服务的 `snapshotCurrent`／`tenantCurrent`
在调用方原事务中取得或重入治理门控，直接使用 `lock` 返回的租户、空间、成员事实验证；不再通过普通 `find` 重读这些事实。
多范围提交须先由 `WorkspaceGovernanceLocks` 按完整范围排序取得全部门控，再逐项判定。

以下新增 SPI 是必需实现的方法，没有委托普通查询的默认回退：

| SPI                 | 当前读取方法                                        | 使用边界                                             |
|---------------------|-----------------------------------------------------|------------------------------------------------------|
| IdentityProvider    | findCurrentBySubjectId                              | 持主体门控后重检身份状态与类型                       |
| TenantStore         | findCurrentPersonal                                 | 主体门控下核对个人初始化关联                         |
| PolicyStore         | findCurrentTenantPolicy／findCurrentWorkspacePolicy | 对应范围门控下读取当前政策                           |
| PreferenceStore     | findCurrent                                         | 初始化、偏好版本校验、默认空间保护及命令结果引用校验 |
| CommandReceiptStore | findCurrent                                         | 取得门控后核对已提交的胜出回执，不能因旧快照重复执行 |

`lock`／`findCurrent*` 必须参与原连接、原事务，看到取得治理门控前已提交的变更及本事务自己的写入；
即使外层事务此前建立过普通读快照，也不能返回该旧快照、MyBatis 会话缓存、应用缓存或只读副本的数据。
数据库适配可以使用对应的锁定当前读取，但不能另开事务模拟新鲜结果，也不能在失败时回退普通查询或伪装未找到。 政策／成员变更继续与父范围门控及
epoch 同事务协作。取得锁不自动证明授权，未知政策版本、撤权、停用及权威读取失败仍拒绝。

个人初始化、空间创建／改名、偏好更新及命令重放均已切换到当前读取；普通展示查询 API 保留原签名。
当前规则测试通过分离旧普通读取与实时锁定读取，覆盖旧快照仍允许而当前事实已撤权／降级／归档／关闭／收紧政策的拒绝，
以及最新身份、个人关联、偏好版本、回执和授权版本的使用。真实数据库隔离级别、行锁及跨实例验证仍由 app 存储适配阶段完成。

## 外层事务中的组合取锁

`WorkspaceGovernanceLocks` 在事务资源中保存主体及租户父锁的取得记录，所有默认写入、当前授权读取、初始化和偏好路径共享它。
记录仅用于顺序检查，每次重入仍从 Store 当前读取事实，不缓存授权或跳过身份重检。资源按实际事务隔离，不用 JVM 锁代替数据库锁。

- 新主体门控按稳定 subjectId 的 String.compareTo 顺序递增，必须在任何租户父锁前取得；已持有主体可重入。
- 新租户父锁按 tenantId 的 String.compareTo 顺序递增；已持有父锁可重入，不受当前最大 ID 限制。
- 租户父锁必须是排他锁，并覆盖该租户下全部空间、成员、政策及后续 ACL 的治理锁和写入。
  在已持有的父锁下可组合不同空间操作，不要求每次新增子锁都排在此前成员锁之后；相同租户的竞争事务已由父锁串行。
  首建同事务插入的父记录也由插入锁保护；默认结果读取仍会登记并重入该父范围。
- 多范围调用先检查完整新增父范围，再申请存储锁。逆序时抛出 GovernanceLockOrderException 并标记原事务必须回滚； Store
  取锁失败同样使锁状态失效。捕获异常后不能继续取锁或提交，授权入口不会将顺序错误转成普通拒绝结果。

组合命令可先通过 `WorkspaceWriteGuard.inCurrentTransaction(actor, allRequests, work)` 锁定并授权完整目标集合， 然后在
work 内按业务需要调用多个公共命令；也可按递增父范围调用。同一租户内的组合、重新读取及新建空间仍支持。
涉及多个主体的受信内部流程须在第一个租户锁之前，通过 WorkspaceGovernanceLocks.subject 按序取得全部主体门控。 自定义执行器
lock 回调、当前事实读取及治理命令均不能直接取 Store 锁绕过共享记录。

个人初始化及偏好结果会预先锁定全部候选父租户；个人回执重放还包括原结果中的偏好引用。
缺失偏好目标仍由后续授权过滤，不因为预先取父锁而获得访问资格。真实数据库集成需验证相同父锁协议、事务资源生命周期、
回滚标记和两实例相反业务顺序的行为；本轮仅在 core 默认服务和测试适配中完成规则实现。

## 第二步默认服务

| 实现                                | 已提供能力                                                                                                       |
|-------------------------------------|------------------------------------------------------------------------------------------------------------------|
| `DefaultPersonalWorkspaceService`   | 有效 USER 初始化；同事务保存租户、默认空间、两级 OWNER、政策、偏好、回执、审计及事件；同键重放及不同键复用关联   |
| `DefaultWorkspaceService`           | 创建、读取、改名及可访问目录；版本条件更新；逐项过滤后分页和计数，不暴露候选 total                               |
| `DefaultWorkspacePreferenceService` | 本人启动／最近／收藏偏好，保留默认关联；更新只接受当前可访问目标；读取时将失效启动偏好回退默认空间并过滤失效引用 |
| `DefaultScopeResolver`              | 稳定主体重检、两级成员资格、范围匹配、政策版本及生命周期验证                                                     |
| `DefaultAuthorizationService`       | 租户／空间基础治理、资源创建资格及 AI 入口资格；check／require／batchCheck 共用规则，未知动作及政策版本拒绝      |
| `DefaultWorkspaceWriteGuard`        | 调用方实际事务内按确定顺序锁租户、空间及成员，再要求当前动作资格；未接入 ACL 的业务资源拒绝                      |
| `DefaultCommandExecutor`            | 规范化长度编码摘要、完整操作身份、同事务回执及重放授权验证；回执冲突回滚领域写入                                 |

`DefaultWorkspaceServices` 是无框架装配入口。app 传入 IdentityProvider、事务、各 Store、审计、Outbox、ID 生成器、Clock
和政策模板，分别取得上述服务。所有依赖必须真实实现，不能将测试用内存存储注册为生产适配。

基础动作针对服务端确认的租户／空间根引用：workspace 类型的 resourceId 必须等于当前 workspaceId，tenant 类型必须等于当前
tenantId。article:create 等创建动作在父空间根上判断，不对尚不存在的文章构造已授权资源。已有文章／模型的 read、edit、use
等需要后续可信描述、ACL 和领域条件，当前默认拒绝；治理角色或 createdBy 不提供内容读取资格。基础 invite／member:manage
判断也不替代后续成员命令对目标角色的检查。

`AccessRequest.scope` 使用 `AccessScope`：租户必填，空间可选；租户治理使用 `AccessScope.tenant(tenantId)`， 或
`DefaultAuthorizationService.tenantRequest`。完整空间请求仍可通过原 `AccessRequest(Scope, ...)` 构造； JSON 的 scope
字段结构不变，租户请求可省略 workspaceId。公共业务 `Scope` 仍要求租户和空间双 ID，未放宽 AI 的执行范围。 Java
调用方需要完整空间范围时显式调用 `request.scope().requireWorkspace()`，不能将仅租户授权用作空间执行许可。

租户根治理独立读取主体、租户、租户成员、租户政策及生命周期；即使旧请求附带空间，该空间的成员资格或状态也不参与租户治理。 租户
OWNER／ADMIN 没加入任何空间时仍可按租户预设治理，但不会因此取得空间或私有资源访问资格。
最终门控对混合请求统一按主体、排序租户、排序空间、租户成员、空间成员取锁；仅租户请求不锁空间。 仅租户授权的
`AuthorizationVersions.workspaceEpoch`、`workspaceMembershipGeneration`、`workspaceMembershipVersion`
为可空 Long 且全部为空，resourceVersion 为租户版本；空间授权保留实际空间版本值，适配器不得把空值当成有效空间资格。

当前范围读取支持 ACTIVE 和 ARCHIVED；ARCHIVED 只允许空间读取／发现及符合角色的恢复资格，基础业务写入拒绝。CLOSING／DELETED
不进入日常目录；未来关闭进度通过独立受限入口读取。系统默认空间可以改名，基础预设禁止归档、关闭及负责人转交；个人租户保持本人托管，租户
OWNER 转交预设仅针对组织租户。未实现的成员、组织、授权管理和生命周期 API 不装配为空服务。

政策模板可由 app 显式传入受支持的连接白名单。`conservativePersonalDefaults()` 的协议／供应商／目的地列表为空，个人凭据许可不等于允许任意连接；不会为尚未接入的
AI 模型生成可执行资格。Schema 和 rolePreset 当前只支持版本 1，未知版本拒绝，不静默按旧预设放行。

偏好收藏最多 100 个；批量权限最多 100 项。偏好查询不偷偷保存过滤结果或覆盖其他标签的
scope。默认空间本身不可访问时明确拒绝并交由恢复流程处理，不随机跳入别人的空间。关键写命令在持有治理门控的同一事务内检查资格，重放只验证原结果的当前可见资格，不重复执行或恢复旧权限。

## 验证及下一步

`CurrentReadWorkspaceServicesTest` 覆盖外层旧快照与实时锁定读取分离；`TenantAuthorizationServicesTest`
覆盖无空间成员的租户角色矩阵、租户与空间资格隔离、租户治理当前读取及混合范围取锁。
`DefaultCommandExecutorReceiptTest` 另覆盖 Void 首次执行／幂等重放、当前权限与身份重检、损坏回执拒绝及结果／回执失败回滚。
`TransactionGovernanceLocksTest` 覆盖外层组合调用、父锁与主体顺序、完整范围预检、跨实例共享状态、异常捕获后强制回滚及状态清理。
执行器、空间规则、公共契约、JSON／枚举序列化及包边界共 112 项相关测试通过。归属未知码断言改用已有 `MyEnum.fromValue`
，不再引用不存在的枚举工厂方法。

原规则测试覆盖并发初始化、同键重放／冲突、不同键复用、失败回滚、版本冲突、角色与政策上限、租户／空间隔离、可见
total、多批候选扫描、默认空间保护、失效偏好、门控及权威查询失败。边界测试也扫描新 application 包。

测试存储只位于 src/test，通过串行事务和快照回滚验证规则；它不证明真实数据库行锁、隔离级别或跨实例并发已落地。这些必须在第三步
app 适配中用数据库集成测试验证。

使用 JDK 21，在 `backend` 下执行：

```shell
mvn -o -pl arte-core -am \
  -Dtest=TransactionGovernanceLocksTest,DefaultCommandExecutorReceiptTest,DefaultWorkspaceServicesTest,CurrentReadWorkspaceServicesTest,TenantAuthorizationServicesTest,PlatformContractTest,PlatformEnumSerializationTest,PlatformPackageBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

下一步由 app 实现 `arte_platform_` 表、稳定身份映射、主体与范围行锁、同库事务、事件投递及首批 HTTP；身份迁移仍核对原 AI
subjectId，不修改历史 scope 或账本。资源授权、邀请／组织与关闭分别按后续阶段实施；本轮不切换 `ainew` 固定授权路径。
