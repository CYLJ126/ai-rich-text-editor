# 公共身份适配：第 3 步第 1 批

本批已实现账户当前状态核验、稳定主体映射及首批身份 SPI 的 app 适配。未创建空间／成员表，未开放空间 HTTP，未迁移资源归属或预算。

## 统一来源与调用

- `RbacAccountReader` 复用 `UserMapper` 的身份专用查询，读取 `appDataSource` 的 `arte_rbac_user`，不走用户缓存、创建人过滤或
  ThreadLocal 数据源路由。只有 `StatusEnum.DOING` 有效；INITIAL／DONE／CLOSED、空值及未知状态均不授予有效主体资格。旧 CLOSED
  同时表达停用／注销，不能自行区分两者，映射为 SUSPENDED；明确退休映射或账户不存在时为 DELETED。
- `UserServiceImpl.loadUserByUsername` 直接调用已有 `baseMapper.selectLoginByName`，一次读取当前账户状态、创建时间、密码和手机／邮箱，不再经过
  `RbacAccountReader` 后重复 `getById`。有效账号还须核对权限缓存主键，继续使用既有角色、菜单、操作权限服务；停用、不存在、同名多记录、身份资料不完整或缓存主键不匹配均拒绝登录。
- `OnlineServiceImpl.getOnlineInfo` 按会话账户主键读取当前状态，核验名称及账户创建时间，再读取与主键一致的 RBAC
  权限。改名、停用、删除、旧账户主键被不同创建记录复用时拒绝旧会话。不凭旧用户名查找同名新账户。
- `TokenServiceImpl.getUserDetailsByToken` 复用同一在线身份核验路径；过滤器不再二次查询在线信息。请求认证保留 `JwtUserDto`
  账户主键，`Authentication.getName()` 继续返回用户名。账号权威数据库不可用时拒绝认证并返回 503。请求边界清理
  UserContext，异步 SecurityContext 仍按原请求属性保存和恢复。
- 新 Token 包含账户主键、创建时间及随机 jti，防止重复登录／同名重注册生成同一 Redis 会话键。升级前没有 accountCreatedAt
  的在线记录不回填为当前账户，必须重新登录。密码只用于登录校验，请求认证上下文中不保留密码。
- `RbacIdentityProvider` 实现 `IdentityProvider`；HTTP 后续通过 `actor(Authentication)` 建立
  TrustedActor，核验会话主键、名称、创建时间与当前账户一致。不能将请求体的 subjectId 或一个普通用户名字符串当作已认证身份。
- `findBySubjectId` 按不可变关联读取当前账号，不按保存的旧用户名匹配。`findCurrentBySubjectId` 只允许在绑定到同一 app 物理
  DataSource 的实际事务内使用，通过锁定读核验最新映射和账号；不能默认委托普通快照查询。

账号 SQL 和结果映射统一在既有 `UserMapper.xml` 中，`UserMapper` 接口提供 `selectLoginByName` 和三个身份查询方法。登录查询额外加载密码、手机／邮箱；
`selectIdentityByName`、`selectIdentityById` 和 `selectCurrentIdentityById` 仍只加载身份所需的四个字段，不加载密码。XML 中通过
`useCache="false"`、`flushCache="true"` 禁用二级缓存并在执行前清理会话查询缓存；`FOR UPDATE` 每次实际执行，锁保持至调用方事务结束。

登录通过既有 Spring Service Bean 调用，`NOT_SUPPORTED` 暂停既有事务管理器管理的外层路由事务，再由 Mapper 切面在获取连接前选择
app 库；结束后恢复外层事务及其连接。仅在 Mapper 调用时切换线程路由无法更换已绑定的事务连接，因此不能省略这一边界。合并的是登录凭据的两次读取；权限缓存未命中时仍会沿用既有
RBAC 查询，不表示整个登录过程只有一条 SQL。在线会话及公共身份核验继续使用 `RbacAccountReader`。

`RbacAccountMapperConfiguration` 装配同一 `UserMapper` 接口的 `identityUserMapper`，固定使用物理 `appDataSource`，与
`JdbcPrincipalIdentityStore` 共享 Spring 事务连接。它仅通过限定名称注入，不参与默认候选选择；内部工厂／模板不注册为全局
Bean。原 `userMapper` 和其他 Mapper 仍由既有 MyBatis 自动配置／扫描器装配，沿用原路由事务和业务拦截器。
`DataSourceSwitchAspect` 排除 `identityUserMapper`，避免身份读取修改调用方的路由上下文。这是同一账户表和查询定义的不同数据源绑定，不建立第二套账户或重复账号
SQL。

稳定映射保存在 `arte_platform_principal_identity`，以 `(provider=arte-rbac, external_user_id=现有用户主键)` 唯一关联
subjectId，不建立第二套用户账户。subjectId 不随改名改变；retire 使用 expectedVersion 并保留关联，不允许再绑定。external_created_at
保存旧账户 create_time 的原始值，检测创建记录不同的主键复用；账户主键及创建时间不得被人工复用／重写来伪装原账户，账户删除治理应先明确退休映射。

## 账号 ID 与 64 位兼容

账号 ID 在 UserPo／UserDto、UserOnlineInfo、UserParam、UserMapper、账号读取、身份绑定和切换上限中统一使用 Long／long，数据库使用有符号
BIGINT。公共 subjectId 及 external_user_id 保持原字符串值；没有重新生成或迁移既有主体。ainew 旧上下文兼容作用域按 Long
解析数值账号 ID，UUID 主体仍不作为旧数值账号使用。

app 的 BaseQueryParam 抽出不含主键的分页／查询公共字段；BaseParam 继续声明 Integer id，供角色、菜单、文章等原有资源使用，UserParam
独立声明 Long id。没有在同一继承链中隐藏两个不同类型的 id 字段。

账号和在线信息的 HTTP JSON id 以十进制字符串输出，用户参数也使用字符串 ID，避免 JavaScript 大整数精度丢失；旧整数数字输入仍能转换为
Long。新 JWT accountId 同样输出字符串。已签名旧 Token 的数字 accountId 不影响原验签及按 Redis 会话核验路径。前端
CurrentUser 已增加 id?: string，用户编辑页原本按字符串 URL 参数和原始数据传递 ID，不做 Number／parseInt 转换。

当前 app 使用 JsonJackson3Codec，实际 Codec 测试已验证旧缓存中数值 id=7 能读取为 Long，以及完整 Long.MAX_VALUE
的账号／会话可以往返读写。此变更无需批量删除当前 JSON 用户缓存或强制退出具备有效创建记录的旧会话；升级前缺少
accountCreatedAt 的会话仍沿用已有重新登录要求。

已有库执行 scripts/arte-rbac-user-id-bigint-migration.sql，新建库使用已更新的
arte-rbac-ddl-mysql.sql。先升级数据库，再整体切换应用；保留现有账号值、自增及主键约束。迁移不调整角色／菜单等其他主键或按用户名保存的
RBAC 关系。仓库中未发现指向账号表的外键；部署前以实际库为准核对外键及表定义。脚本已提供，尚未连接实际 MySQL 执行。

## 部署与旧主体核对

身份映射默认关闭，旧账户状态核验和新 Token 规则随本批代码生效。应用启动不执行 DDL、回填或账户迁移。

1. 在 app 库执行 `scripts/arte-platform-identity-ddl-mysql.sql`。SQL 尚未对实际数据库执行。所有新的 UTC 时间读写需统一
   JDBC／连接会话时区为 UTC；external_created_at 保持既有账户时间值不重新解释。
2. 在切换期间暂停注册或采用受控切换，记录旧账户 `MAX(id)` 作为 legacy-user-id-ceiling；旧账户主键不可复用。这个值不是当前请求动态计算的最大值。
3. 核对已有 ainew 用户的原 subjectId 与实际 arte_rbac_user.id，一账户多主体、主体被不同账户声明或其他历史歧义均拒绝自动迁移。bindings
   是经核对的部署输入，不是按用户名猜测的自动关联。没有 AI 历史的既有账户也先明确核对，再配置一个新生成的 UUID。
4. 配置示例中的用户主键和主体只是格式示例，替换为已核对值；默认空间不能替换原 AI scope，预算和历史所有者也不能顺带迁移。

```yaml
arte:
  platform:
    identity:
      enabled: true
      legacy-user-id-ceiling: 123
      bindings:
        - user-id: 42
          subject-id: old-reviewed-ai-subject-id
```

已保存关联优先读取；配置绑定与保存值冲突时返回 IDENTITY_CONFLICT，绝不覆盖。没有关联的旧账户（id
不大于切换上限）必须先核对；新账户（超过上限且无同名固定 grant）首次认证到公共服务时生成不透明 UUID。命中同名固定 grant
时也必须有明确绑定，包含停用的 grants，避免被删除／修改的配置成为绕过核对的依据。

映射创建加入同一 appDataSource 本地事务，按账户行门控并由数据库双唯一约束兜底。若调用方已持有其他数据源／旧路由 DataSource
的事务，拒绝在其中偷偷另开物理事务提交映射。后续 PublicTransactionRunner 与公共 Store 必须使用同一物理
DataSource；不能把路由库与物理库两个事务描述为原子提交。

## ainew 兼容范围

公共身份和 ainew 均启用，且应用没有提供其他 ExecutionAuthorizationResolver 时，app 装配
`RbacFixedExecutionAuthorizationResolver`。它复用原 FIXED 授权的 tenantId、workspaceId、scopes、grantRef，额外核验当前账户有效及原
subjectId 与公共主体一致；阻塞身份访问在专用 newAiPersistenceScheduler 上执行。身份关闭时继续使用原 AI
默认解析器；自定义解析器由实现者承担同等身份一致性检查。

本批没有把固定 grants 转成公共空间成员，也没有开放动态授权。资源发现／管理／准入、撤权门控及后台收尾仍需后续批次接入。仅开身份开关不能宣称完整
AI 公共权限迁移完成。账号名称变更后，固定 grant 的受信名称配置仍需由管理者核对更新，原稳定主体与范围不变。

沿用旧认证入口的返回格式；公共 HTTP 的完整错误码与状态适配留在 HTTP 批次。此次同时修正 ainew 运行配置中的
snapshot-retention 字段名，构建占位符沿用旧 workspaceSnapshot-retention 变量以兼容已有 profile。

## 验证

使用 JDK 21 的 Maven 离线测试，共 93 项相关测试通过（app 74，AI 上下文回归 14，core 序列化回归 5）。受限运行环境中预加载已有
Byte Buddy agent，避免 Mockito 动态附加 JVM；没有修改项目的默认测试插件配置。数据库测试使用 H2 MySQL 模式的实际
MyBatis／JDBC、本地事务、锁和唯一约束；验证并发唯一、外层回滚、稳定映射、停用／注销、同名新账户隔离、主键创建记录冲突、原 AI owner
保持及可信会话核验。新增真实 MyBatis 自动配置和数据源切面测试，验证原 Mapper 默认注入／CRUD 回滚、固定物理库及路由上下文保持、同一
SqlSession 的身份查询缓存失效、账号锁持续到事务结束、不加载密码及无效状态拒绝。登录／在线身份测试核验当前密码和原菜单权限；新增登录查询合并、无效账号／权限缓存主键冲突拒绝、SQL
参数绑定、手机／邮箱映射，以及已绑定其他库时暂停路由事务、读取 app 账号并恢复原连接的验证；真实 SecurityFilterChain 测试覆盖
Mono／SSE 异步恢复、无会话跨请求串用、停用拒绝及空 Bearer。

新增 Long.MAX_VALUE 的登录、会话、JWT、Mapper CRUD／锁定读、受信配置绑定、公共主体映射及旧兼容上下文验证；使用实际 HTTP Mapper
和 Redis Codec 验证大整数字符串、旧数字输入与查询字段继承。

前端全量 TypeScript 检查仍存在既有类型错误；临时副本移除本次 CurrentUser.id 声明进行对照，规范化文件路径后，本次没有新增诊断。未修改不相关的前端类型问题。

MySQL 部署还需下一批验证真实 InnoDB 锁、REPEATABLE READ 当前读取、唯一键竞争和公共事务装配。测试不连接运行中的
MySQL／Redis／模型，也不代表实际库已迁移。
