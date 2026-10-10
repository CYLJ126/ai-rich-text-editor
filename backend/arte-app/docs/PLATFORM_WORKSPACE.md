# 公共应用服务：第 3 步第 3 批

本批把已完成的身份、MyBatis 存储和事务 SPI 装配到 core 默认服务；新增响应式会话适配。默认关闭，不执行 DDL、不在启动时初始化用户空间，不注册
HTTP，不迁移旧文章、标签、模型或账本，不切换 ainew 的固定授权路径。

## 服务与装配

`platform.workspace.PlatformWorkspaceConfiguration` 创建一个 `DefaultWorkspaceServices`，六个公共 Bean
直接返回该实例的服务，组合取锁、当前授权、幂等和政策规则仍由 core 实现。

| Bean                         | 接口／职责                                                          |
|------------------------------|---------------------------------------------------------------------|
| personalWorkspaceService     | PersonalWorkspaceService：本人个人租户与默认空间初始化              |
| workspaceService             | WorkspaceService：目录、读取、创建、改名                            |
| workspacePreferenceService   | WorkspacePreferenceService：本人启动、最近访问和收藏偏好            |
| workspaceScopeResolver       | ScopeResolver：真实身份、两级成员与政策校验                         |
| platformAuthorizationService | core AuthorizationService：公共基础动作判断，不替代原 RBAC 功能权限 |
| workspaceWriteGuard          | WorkspaceWriteGuard：原事务内门控、当前授权与业务写入               |
| reactiveWorkspaceOperations  | ReactiveWorkspaceOperations：已认证会话到公共 API 的 Mono 适配      |

各 Store 通过 core SPI 注入；事务使用限定 `platformTransactionRunner`，时钟使用限定 `platformWorkspaceClock`
（UTC，非默认候选，不干扰原时钟注入）。不增加默认事务管理器，不改旧 Mapper 扫描或 RBAC Bean。未实现的成员、邀请、资源
ACL、组织与关闭服务不注册。

## 启用与默认政策

先完成身份核对及两批 DDL；沿用第一、二批的配置，然后增加：

```yaml
arte:
  platform:
    identity:
      enabled: true
      # 保留已核对的 legacy-user-id-ceiling 与 bindings
    persistence:
      enabled: true
      # 沿用有界 workers、queue-capacity、超时、Redis 和 Outbox 配置
    workspace:
      enabled: true
      personal-policy:
        creation-types: [ article, collection, model, assistant ]
        personal-credentials-allowed: true
        protocols: [ ]
        providers: [ ]
        destinations: [ ]
        export-types: [ article, collection ]
        egress-types: [ article, collection ]
        share-target-kinds: [ SUBJECT, SPACE_ROLE, WORKSPACE ]
```

配置 `workspace.enabled=true` 时必须同时启用身份和持久化，缺少
SPI／基础设施会启动失败，不以空服务或内存存储降级。开启身份仍要求明确核对旧账号边界，不能复制示例后省略旧身份迁移。

政策未填写时采用上述保守默认值；明确空集合表示禁止，连接白名单为空不表示通配。首版类型仅接受
article、collection、model、assistant；白名单每项使用公共 ID 校验、每组最多 100 项，未知配置拒绝。访客 AI
和额外角色创建扩权不通过本批配置开放。模型／助手创建类型仅表示政策上限，不代表模型发布或资源 ACL 已完成。

模板只用于新建个人租户；同租户新增空间继承已保存的租户政策。改变部署模板或重启不会覆盖已有租户／空间政策；已有政策修改需后续显式治理命令、版本与
epoch 更新。各实例应部署相同模板，历史政策以数据库为权威。

## 同步与响应式调用

同步调用通过 `RbacIdentityProvider.actor(authentication)` 核验可信登录会话后调用 core API。JDBC 为阻塞实现，必须在允许阻塞的线程执行；写命令由
core 开启／加入本地事务。HTTP 请求不能绑定 TrustedActor、subjectId 或权限声明。

响应式调用注入 `ReactiveWorkspaceOperations`：

```java
return workspace.initializePersonal(authentication, new CommandMeta(operationKey, traceId));

        return workspace.

create(authentication,
        new WorkspaceCommands.CreateWorkspace(meta, tenantId, name, description));
```

该适配支持初始化、目录、读取、创建、改名、偏好读写、Scope 验证、单项／批量授权。调用时复制账户 ID、名称和账户创建时间，拒绝未认证或不完整会话；不保留
Token、密码、原可变在线用户对象或菜单角色。订阅后在已有 `platformPersistenceScheduler` 上进行身份数据库核验和完整公共调用；不在入口线程访问
JDBC，不读取切线程后的 UserContext。

Mono 惰性执行，每次订阅重新核验当前数据库身份与授权，不 cache 允许结果，不自动 retry，不新建 operationKey。普通读不外包长事务；普通写由
core 控制事务。队列饱和返回 AUTHORITY_UNAVAILABLE，后续 HTTP 适配为 503。线程和队列上限、查询和事务超时沿用持久化配置，并按连接池余量设置；响应式调度不把
JDBC 变成非阻塞驱动。

订阅取消不能证明 JDBC 命令已经回滚，也不能替代业务取消。提交结果不确定时使用原 operationKey 进行幂等重试／查询。当前批次不新增
HTTP 回执查询。

## 组合写入与分布式边界

业务需要在同一物理 app 库事务中执行公共门控和自身写入时：

```java
return workspace.inWorkspaceTransaction(authentication, requests, () ->{
        // 同步本地 Mapper 写入；领域自身资格和版本仍须校验。
        return businessMapper.

update(...);
});
```

整个回调在同线程事务内执行，先核验主体、按 core 规则获取数据库门控，再当前授权并执行业务写入。回调失败整体回滚；返回
Publisher 或 CompletionStage 明确拒绝并回滚，不能把尚未执行的异步工作视为已提交。回调不能切线程、block 或等待外部网络。基础
WriteGuard 当前仅支持已实现的租户／空间目标，不能借此宣称文章正文或模型 ACL 已接入。

响应式入口不会传播调用线程上已有的 JDBC 事务。已有同步业务事务应直接调用 core API／WriteGuard，并确保是物理 appDataSource
的同一事务；不能在旧路由事务中订阅 Mono 并声称原子提交。

跨实例唯一性、版本和授权线性化由既有数据库门控、联合约束、CAS 及回执保证，不新增 JVM 锁或 Redis 锁代替数据库提交。Redis
继续承担跨实例短期游标及可靠投递协调；事件、审计、回执与业务同事务写入，消费者和 Inbox 在后续批次接入。

目录和 batchCheck 复用批量授权事实读取与 keyset 候选扫描，不逐项查库、不跨请求缓存。精确可见 total
仍需扫描全部候选；这批验证查询结构和并发正确性，不承诺无限规模目录的恒定延迟，也不替代生产压测。

## 验证与后续

新增测试：

- PlatformWorkspaceConfigurationTest：启用条件、唯一服务图、RBAC／时钟／事务 Bean 兼容、政策绑定和非法配置拒绝。
- ReactiveWorkspaceOperationsTest：惰性执行、最小身份快照、工作线程核验、认证拒绝和有界队列过载。
- PlatformWorkspaceAssemblyIntegrationTest：真实生产配置／组件扫描／Mapper，完整初始化、目录、创建、改名、偏好和授权流程；独立
  Spring 实例并发、账号停用、越权、原事务业务回滚及政策重启保护。

H2 快速验证可将上述三项加入已有公共身份与持久化回归。真实 MySQL／Redis 沿用 PLATFORM_PERSISTENCE.md 的隔离测试参数；不得传业务数据库。测试中的
H2 DDL 预处理支持格式化后的多行 SET，不修改生产 DDL。

下一批开放首批 HTTP、请求 DTO、分页／错误适配和接口级兼容验证。用户默认空间通过显式幂等初始化获取；注册事务联动、历史数据迁移、资源分享和组织治理分别交付。

2026-10-10 验证记录（JDK 21）：

- 新增装配／响应式／生产配置闭环 15 项通过。
- core 公共契约与默认服务回归 137 项、app 身份／持久化／应用装配回归 107 项、ainew 配置与执行上下文回归 31 项通过，共 275 项。
- 隔离 MySQL 8.4／Redis 7.4 集成 31 项通过，包含实际生产配置的 6 项应用闭环；临时测试容器已清理，未连接业务数据库。
- 性能验证覆盖批量事实查询结构、目录 keyset 和队列过载拒绝；尚未执行生产容量、吞吐或尾延迟压测。

扩大到原 ainew 聊天／会话回归时，ChatHttpIntegrationTest、ConversationHttpIntegrationTest、ConfigurationAssemblyTest 合计出现
19 项失败／错误。既有 Mapper XML 使用 `#{workspaceSnapshot}`，Java Mapper 的参数仍声明 `@Param("snapshot")`；当前 HEAD
中已存在该不一致，且失败发生在仅运行 arte-ai、尚未进入 arte-app 装配的阶段。本批未改动这些 AI Mapper，也不能据此宣布全部旧
AI 回归通过。修正参数绑定并完成原 AI 回归后，再确认完整新旧兼容验收；公共空间装配交付与此分别记录。
