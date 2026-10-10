# 公共持久化：第 3 步第 2 批

本批在 `com.arte.app.service.platform` 实现公共空间数据库 SPI，在 `com.arte.app.platform.persistence`
装配事务、Mapper、响应式执行边界、Redis 游标和 Outbox 投递基础设施。默认关闭；不执行 DDL、不回填旧资源、不注册空间
HTTP。本持久化配置自身不装配 core 默认应用服务。第 3
批已在独立配置中完成应用装配，见 [公共应用服务说明](PLATFORM_WORKSPACE.md)；第 4 批开放 HTTP。

## 实现与职责

| 适配                                                                  | 职责                                                                                              |
|-----------------------------------------------------------------------|---------------------------------------------------------------------------------------------------|
| TenantStoreServiceImpl／WorkspaceStoreServiceImpl                     | 完整范围、个人关联唯一、治理当前读、版本 CAS、创建事实不可变、索引游标扫描                        |
| TenantMembershipStoreServiceImpl／WorkspaceMembershipStoreServiceImpl | 两级成员独立存储、范围过滤、成员代次不倒退、版本更新，不实现授权算法                              |
| PreferenceStoreServiceImpl／PolicyStoreServiceImpl                    | 系统默认关联不可改、结构化政策与收藏；UTC Instant 不丢纳秒                                        |
| WorkspaceAccessFactsStoreServiceImpl                                  | 每批最多 100 个租户／空间，最多 7 次集合查询；不循环调用单项 find，不跨请求缓存                   |
| CommandReceiptStoreServiceImpl／PlatformJson                          | 长度编码的完整命令身份 SHA-256 唯一键；保存原始身份并核对碰撞；固定 View Schema，无 Java 多态类名 |
| JdbcPublicTransactionRunner                                           | 同线程同 appDataSource 的 REQUIRED 事务；事务共享状态、挂起／恢复、强制回滚                       |
| PublicOutboxStoreServiceImpl／AuditSinkServiceImpl                    | 事件、审计与业务命令同事务，任何必要写入失败整体回滚                                              |
| PlatformPersistenceExecutor                                           | Mono 惰性执行，整段阻塞数据库事务放到有界调度器，不在事务内部切换线程                             |
| RedisWorkspaceCursorStore／RedisOutboxPublisher                       | 跨实例短期游标、Redis 发布协调、Redis Streams 投递；数据库保留事件权威                            |

存储适配直接固定到物理 `appDataSource`，与第一批身份适配共享 Spring JDBC 连接，不通过动态路由或旧 `create_by` SQL
改写。没有新建第二套账户。新建公共表不使用用户名充当资源归属，`created_by` 为稳定 subjectId。

## MyBatis Plus 服务实现

公共空间的 10 个数据库 SPI 实现均使用现有 ServiceImpl 体系。具体实现位于 `service.platform`，通过 `@Service` 与
`arte.platform.persistence.enabled=true` 条件注册，直接实现 core SPI；构造器通过限定 Bean 名注入平台
Mapper、PublicTransactionRunner、JSON 和游标基础设施。不增加重复继承 core 契约的 app 服务接口，也不将通用 IService CRUD
暴露给治理业务。

| core 契约                 | app 实现                             |
|---------------------------|--------------------------------------|
| TenantStore               | TenantStoreServiceImpl               |
| WorkspaceStore            | WorkspaceStoreServiceImpl            |
| TenantMembershipStore     | TenantMembershipStoreServiceImpl     |
| WorkspaceMembershipStore  | WorkspaceMembershipStoreServiceImpl  |
| PreferenceStore           | PreferenceStoreServiceImpl           |
| PolicyStore               | PolicyStoreServiceImpl               |
| WorkspaceAccessFactsStore | WorkspaceAccessFactsStoreServiceImpl |
| CommandReceiptStore       | CommandReceiptStoreServiceImpl       |
| PublicOutboxStore         | PublicOutboxStoreServiceImpl         |
| AuditSink                 | AuditSinkServiceImpl                 |

`PlatformStoreSupport` 继承 MyBatis Plus ServiceImpl，共享同一事务的主体／租户／空间门控记录和写入失败处理，不承担授权算法。审计直接继承
ServiceImpl；批量授权事实服务以空间 Mapper 为基础，组合其余 Mapper 做集合查询。core 公共服务负责身份、组合取锁顺序及权限判断，业务应按
core SPI 注入，不通过实现类的继承 CRUD 绕过这些约束。

`pojo.platform` 下的 PO／DTO 精确映射 10 张平台表，DTO 沿用项目继承 PO 的结构，不继承旧 BaseDto。ID 由平台提供，以 INPUT
写入；枚举按 core 的整数值保存，未知／缺失枚举在转换成 core 值对象时拒绝；JSON 原文保持 String；秒／纳秒保持
Long／Integer；Outbox 日期使用表示 UTC 的 LocalDateTime。`PlatformRecords` 在 app 中显式转换领域记录与 DTO，保持完整范围和
Instant 精度，不给 core 引入 MyBatis 注解或基类。

`platform.persistence.mapper` 下的 10 个 Mapper 仅由 PlatformMybatisConfiguration 显式注册，固定绑定物理
appDataSource，位于旧 Mapper 扫描范围之外。工厂和 SqlSessionTemplate 保存在专用会话容器中，不作为全局同类型 Bean，保留旧
MyBatis 自动装配和数据源路由。使用 SpringManagedTransactionFactory 加入原物理库事务；不添加旧创建人过滤、审计改写、自动乐观锁或偏移分页拦截器。一级缓存为
STATEMENT，二级缓存关闭，SIMPLE 执行器，语句超时沿用 query-timeout-seconds。关闭缓存不能替代授权检查和 FOR UPDATE 当前读。

普通单主键读写复用 BaseMapper。复杂逻辑集中在同名 Mapper XML：完整范围查询、FOR UPDATE 当前读、带不可变事实检查的版本
CAS、成员代次、成员创建时复制空间分页键、keyset 分页和批量授权事实查询。XML 全部绑定参数，不拼接客户端 SQL。空间策略 Mapper 保留
`(tenant_id, workspace_id)` 联合主键，通过专用插入／查询／更新方法处理，不虚构单主键或使用通用 updateById。

Outbox 使用 Mapper XML 执行数据库时钟、SKIP LOCKED 批量领取、租约更新、令牌确认及重试。core PublicOutboxStore 负责治理事务中的追加。额外的
app `OutboxDeliveryService` 包含实际的 claim／acknowledge／retry 操作及 Delivery 值对象，供 Redis 发布器使用；它不是重复的
core 接口，也不将租约细节扩散到 core。

PlatformPersistenceConfiguration 仅保留基础设施 Bean，数据库存储服务由组件扫描装配。旧 Jdbc*Store 和 JdbcPlatformSupport
已移除，事务 runner 继续使用 Spring JDBC 事务管理器；第一批身份映射实现和已有 RBAC 账户体系保持原装配。本次不改 core
SPI、数据库 DDL、HTTP 或旧 AI 服务。

## 验证范围

PlatformPersistenceIntegrationTest 已全部切换为真实
Mapper／ServiceImpl，覆盖多实例初始化、幂等重放、业务／审计／回执／事件整体回滚、当前读、稳定游标、默认关联保护、成员代次和两级政策
CAS。授权事实测试使用真实 DataSource 计数，100 个范围仍最多 7 次集合查询。组件扫描测试验证开关与每个 SPI 的唯一注册；身份测试验证新旧
MyBatis 装配共存。Redis 测试验证跨实例游标、租约围栏、失败重试与事件投递。

测试结果记录于文末。测试中的 PlatformServiceTestFactory 仅组装真实 Mapper，不替代生产组件扫描；测试不连接业务数据库。验证是正确性和查询结构验证，不是吞吐或延迟压测。

## Redis、数据库与事务边界

Redis 优先用于可以接受到期／重试的状态：游标使用随机 UUID + TTL，保存完整稳定键和主体绑定；跨实例发布使用 Redisson
带有限租期的异步锁、不同异步 owner，以及 Redis Streams。游标丢失需重新查询目录，不产生新权限。

主体／租户／空间的治理门控、当前身份与政策读取、CAS、初始化唯一性和可靠回执必须由数据库保证。既有 SPI 明确要求原事务持有数据库门控；Redis
租约过期或节点故障不能替代本地数据库提交的线性化点。Redis 锁只是投递协调，数据库批次租约和随机 leaseToken 对迟到确认作围栏。

主体门控先锁既有账号，再锁稳定身份映射，即使偏好行尚不存在也能串行初始化。所有子记录锁和治理写入需要已持父租户门控；存储事务状态验证持锁前置条件，公共应用层继续负责完整的组合取锁顺序、身份及权限。插入的父记录同样由本事务保护并登记。当前读取使用
FOR UPDATE，能看到取得门控前已经提交的变更，不沿用 MySQL REPEATABLE READ 的旧快照。

事务 runner 不注册第二个默认事务管理器，不改变旧 `@Transactional` 选择。相同物理 appDataSource 的既有事务可以加入；绑定路由
DataSource 或其他库的事务会明确拒绝，不能把另开事务说成原子提交。后续业务装配需显式使用物理 app
库事务，并在事务回调内调用公共服务／WriteGuard。事务资源按真实外层事务保存，支持 REQUIRED 重入、REQUIRES_NEW 挂起恢复、跨
runner 共享；失败标记实际连接回滚，即使外层捕获异常也不能提交。

## 性能与可靠投递

- 目录按 `(created_sec DESC, created_nano DESC, tenantId, workspaceId)` 做稳定 keyset 查询；每页仅取 limit+1，最多 101
  条。成员记录复制不可变创建键，通过复合外键核对空间事实；索引使用主体摘要缩短索引长度，查询仍绑定原
  subjectId，摘要不参与授权。改名或边界成员记录移除不导致漏扫。
- 政策规则、收藏、公开回执及事件使用固定结构 JSON；不存模型凭据、文章正文或任意脚本。未知回执 Schema 拒绝，不从存储／客户端提供的类名加载
  Java 类型。
- 固定秒／纳秒列保持完整 Instant 精度，避免 DATETIME (6) 舍入破坏目录顺序；投递租期按数据库时间计算，不依赖节点时钟相等。
- Outbox 通过就绪时间索引和 FOR UPDATE SKIP LOCKED 分批取租约；取租约事务提交后才访问 Redis，网络访问不持有数据库事务。发布后仅以匹配
  leaseToken 确认；过期的未确认租约可再次领取。
- Redis 不可用时命令仍能提交业务和数据库事件，发布失败按 retry-delay 延后。发布成功但 DB 确认失败可能重复投递，消费者必须以
  eventId 去重；不承诺 exactly-once 或跨事务全局顺序。消费端按版本／epoch 防止状态回退，事件不能代替当前授权权威。
- 发布确认仅表示 Redis 已接收，不表示消费者已完成。生产 Redis 需要配置持久化／高可用；不自动裁剪 Stream。Outbox
  已发布原文保留，灾难恢复应根据消费者检查点实施受控重放，不自动删除数据库事件。业务事件消费者、Inbox
  去重、保留期与运维重放入口在后续消费接入批次实现。
- workers 与队列容量有界，MyBatis／JDBC 有查询／事务超时；队列饱和直接拒绝而非无限堆积。queue-capacity 是每个工作线程的队列上限；调整
  workers 时为旧业务和投递保留数据库连接池余量。
- Mono 取消不是业务命令取消，正在运行的 JDBC 提交结果仍可能不确定；使用原 operationKey 查询或重试，不能改键重发。不会在持事务线程中等待
  Redis 或切换 Reactor 线程。

公共 API 仍要求精确可见 total，默认空间列表服务会扫描所有候选计算可见总数；本批优化分页定位和数据库往返，不宣称目录总数查询已经成为常数成本。授权事实必须按批重读；未来大目录改用不含精确
total 的公共契约时另行评审。

## 部署

1. 第一批身份功能及旧 AI 主体核对已完成，先部署／执行 `scripts/arte-platform-identity-ddl-mysql.sql`。
2. 经数据库迁移执行 `scripts/arte-platform-workspace-ddl-mysql.sql`，MySQL 8+／InnoDB；这是新建表脚本，按迁移版本执行一次，不用于覆盖既有库。
3. 所有 app 库连接的会话时区设为 UTC（例如 Druid connection-init-sqls 执行 SET time_zone = '+00:00'），JDBC serverTimezone
   同样设为 UTC。DDL 中的 SET 只影响执行脚本的连接，不能替代连接池设置。
4. 已配置 Redis 后显式启用持久化；投递需另开 outbox-enabled。当前消费者尚未接入时可以保持投递关闭，事件仍可靠保存在数据库。

以下为增量配置；保留第一批已经核对的身份参数，不以示例覆盖原 bindings 和 legacy-user-id-ceiling。

```yaml
arte:
  platform:
    identity:
      enabled: true
      # 保留第一批已核对的 legacy-user-id-ceiling 与 bindings
    persistence:
      enabled: true
      workers: 8
      queue-capacity: 128
      query-timeout-seconds: 5
      transaction-timeout-seconds: 15
      cursor-ttl: 10m
      redis-prefix: 'arte:platform:' # 同一环境实例一致，不同环境分开
      outbox-enabled: false
      outbox-batch-size: 50
      lease: 30s
      poll-interval: 1s
      retry-delay: 5s
```

`PlatformPersistenceConfiguration` 装配基础设施，`service.platform` 通过组件扫描提供真实 SPI Bean；第 3 批的
PlatformWorkspaceConfiguration 通过这些 Bean 构建 DefaultWorkspaceServices；ReactiveWorkspaceOperations
捕获最小会话身份后，在有界线程核验并调用公共 API，不读取切线程后的 UserContext。普通命令由 core 控制事务；组合写入由响应式适配的
inWorkspaceTransaction 包住门控与完整业务回调。

## 验证

JDK 21，在 backend 目录执行 H2 快速回归：

```shell
mvn -o -pl arte-app -am \
  -Dtest=PlatformPersistenceIntegrationTest,PlatformPersistenceConfigurationTest,PlatformMybatisConfigurationTest,AuditSinkServiceTest,RbacIdentityProviderTest,RbacAccountReaderTest,RbacSessionIdentityTest,RbacLoginIdentityTest,PlatformIdentityConfigurationTest,RbacAccountIdSerializationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

真实服务测试仅连接专用隔离实例。测试以 root／platform-test 创建随机临时库并在结束时删除， **只能传专用测试实例**
；不传生产数据库。Redis 使用随机测试前缀，两个独立 Redisson 客户端验证分布式行为：

```shell
mvn -o -pl arte-app -am \
  -Dtest=PlatformPersistenceIntegrationTest,RedisPlatformPersistenceIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dplatform.test.mysql=jdbc:mysql://127.0.0.1:13316/mysql?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true' \
  -Dplatform.test.redis=redis://127.0.0.1:16389 test
```

覆盖真实 DDL／外键、多实例并发初始化、同键重放、完整回滚、版本冲突、默认关联不可修改、稳定游标、100 个范围固定 7
次集合查询、REPEATABLE READ 当前读、事务资源挂起恢复、Redis 客户端失效后恢复、跨实例游标及租约围栏。该验证是正确性与查询结构验证，不是吞吐或延迟压测。

2026-10-10 本次服务迁移验证：app 身份与 H2 持久化回归 92 项全部通过；隔离 MySQL 8.4／Redis 7.4 集成 25
项全部通过。临时测试容器已清理，未连接或修改业务数据库。
