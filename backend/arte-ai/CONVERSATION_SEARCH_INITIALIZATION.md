# 会话搜索启动初始化

MySQL 四表迁移需先执行；应用不执行 SQL DDL。用户已确认执行该脚本，本轮未复核业务库。当前部署配置默认开启初始化、查询和后台同步，重新构建并启动后生效；本次配置修改未重启业务实例。

## 首次初始化

`backend/profile/app.properties` 和 `.example` 已默认开启搜索主开关、启动初始化、查询、扫描、历史回填和定期校准，默认物理代际为
`conversation-search-v2-g01`；修改后需重新构建资源并启动应用。以下环境变量可覆盖部署配置：

```bash
export ARTE_AI_NEW_SEARCH_ENABLED=true
export ARTE_AI_NEW_SEARCH_INITIALIZE_ON_STARTUP=true
export ARTE_AI_NEW_SEARCH_QUERY_ENABLED=true
export ARTE_AI_NEW_SEARCH_SCAN_ENABLED=true
export ARTE_AI_NEW_SEARCH_BACKFILL_ENABLED=true
export ARTE_AI_NEW_SEARCH_RECONCILE_ENABLED=true
export ARTE_AI_NEW_SEARCH_GENERATION=conversation-search-v2-g01
```

AI 主开关 `arte.ai-new.enabled` 也须已开启；依赖使用现有的 ElasticsearchClient、RedissonClient 和配置指定的物理数据源。Java
配置类型不自动生成代际名，部署模板已指定上述默认物理名，可按环境覆盖；已有代际名不得当作丢失索引的重建名称。

对应配置：

```properties
arte.ai-new-search.enabled=true
arte.ai-new-search.initialize-on-startup=true
arte.ai-new-search.sync.generation=conversation-search-v2-g01
arte.ai-new-search.query-enabled=true
arte.ai-new-search.scan-enabled=true
arte.ai-new-search.backfill-enabled=true
arte.ai-new-search.reconcile-enabled=true
```

ApplicationReady 后后台依次核验／创建索引、登记 MySQL 代际和检查点、补齐别名。成功日志为
`AI search initialization ready generation=...`；这表示索引可用，不表示历史回填已完成。索引定义唯一来源为
`SearchIndexSchema.create`，无需另行执行 ES JSON 脚本。初始化自身不写入会话正文。

默认读别名 `conversation-search-read`，写别名 `conversation-search-write`
；首次绑定到指定物理索引。重启只核验兼容性，不改映射、不重置扫描进度、不替换已有别名、不删除旧代际。ES／IK 默认版本均为 9.4.2，规则
v2；部署应按实际固定版本配置。副本数可由运维调整，不作为自动改写或重建依据。

## 初始化后

当前默认通过 `scan-enabled=true` 和 `reconcile-enabled=true` 持续同步；如仅做一次历史回填，可关闭扫描和校准并单独开启
`backfill-enabled=true`，完成后自动停止。初始化先完成，扫描才启动。查询开关单独控制，开放前按 S5
部署交接验证灰度范围、权限及新鲜度；搜索不等待全历史回填。

已有活动别名时，如果 `sync.generation` 指向另一个代际，该目标必须已显式创建且兼容。初始化只核验和登记目标，保持读／写别名原指向。升级与回退仍使用
`ConversationSearchMaintenance.create / prepareActivation / activate` 流程，不以重启替代校准和切换。

## 后台日志

后台源读取（含耐久结果读取）和同步进度 SQL 默认不输出逐条 started/completed；SQL 失败保留 ERROR，耗时达到阈值的成功 SQL 保留
WARN。 前台搜索／聊天查询继续使用原 SQL 诊断策略，日志只输出参数化 SQL，不输出绑定参数或快照正文。

```properties
arte.ai-new-search.diagnostics.sql-enabled=false
arte.ai-new-search.diagnostics.slow-sql-threshold=500ms
```

环境变量分别为 `ARTE_AI_NEW_SEARCH_SQL_ENABLED` 和 `ARTE_AI_NEW_SEARCH_SLOW_SQL_THRESHOLD`；开启逐条日志还需要
`ExecutionSqlDiagnostics` 的日志级别允许 DEBUG。阈值允许 1ms～1m，失败日志不受这两个参数抑制。配置在构建并启动后生效，无需数据库迁移。

`AI search scan` 在首次成功 tick 后输出摘要，之后约每分钟汇总自上次摘要以来的成功 tick：`ticks` 为轮询次数，`leaseSkips`
为未取得租约次数，
`tickElapsedMs` 为累计耗时，`lastTickMs` 为最近一轮耗时；`scanned` 包括 C/T/I 发现及 E 校准候选，`processed` 为尝试处理的任务数。
`changed` 为本轮处理中新建或推进同步版本的条目数；`written` 为成功执行 bulk 并确认的条目数，包含同版本的强制校准写入，不包含独立归档清理的删除量。
两者均为处理次数，不跨轮去重。`pending/failures/missing/rounds` 仍是持久状态快照；`nextDelayMs` 和 `nextRunAt`
为当前计划的等待时间及预计唤醒时间，实际运行受调度队列影响。日志降噪只增加日志和内存计数；后台 SQL 优化见下一节。

## 后台扫描初始化与检查点读取

Worker 对当前代际成功登记后，不再每轮执行代际登记和四个检查点的 `INSERT IGNORE`。只有登记成功才记录初始化状态；失败由后续
tick 重新尝试。 每个 Worker 只记住最近一个已登记代际，运维推进其他代际、切回原代际或重建 Worker 时重新校验，内存状态不随历史代际数量增长。
登记仍为幂等短事务，不重置现有游标、轮次、重试或索引确认进度。

每轮仍从数据库领取租约，并在一次校验 worker/fence/有效期的短事务内读取 C/T/I/E 四个检查点，当前轮复用这份不可变快照，下一轮重新读取。
领取失败即退出本轮，不使用初始化状态代替租约。数据库时钟、各写入操作的租约校验和 ES 请求在事务外的边界保持原样。
检查点缺失时明确失败，不把它解释为零进度；运行期间不得人工删除进度记录，需要修复后重建 Worker 或重启恢复。

`SearchSyncPollingTest` 覆盖初始化失败重试、重启与切代际、竞争租约、失效租约、检查点缺失，以及 SQL 数量。 H2
隔离测试中，完成初始化且无到期扫描／待处理任务、租约不接近到期的一轮实际执行 13 条 SQL，其中四个检查点只查询一次，不含周期状态摘要。
这是同条件下第 3 阶段 22 条 SQL 合并后的结果；接近到期触发额外时钟复核时会增加查询，不能把 13 条作为所有轮次的固定开销。
这不是生产 MySQL 吞吐或延迟指标。无需数据库迁移；重新构建并启动后生效，本次未重启业务实例。

## 自适应后台轮询

`sync.tick`（默认 250ms）是活跃处理间隔。调度器累计当前循环亲自完成的 C/T/I 通道轮次，只有三条通道都到达扫描尾部、期间没有同步版本变化或处理失败、且到期任务排空后，
才把轮间等待按 500ms、1s、2s、4s、5s 逐步增加；上限由 `sync.idle-max` 控制。每轮内部仍按活跃间隔发现／处理，不能因为一个空页或
`pending=0` 就把整个扫描放慢。 强制校准导致的同版本写入不算源变化；发现同步版本变化后立即恢复活跃间隔。重启丢弃内存退避状态，持久检查点和重试时间保留。

```properties
arte.ai-new-search.sync.tick=250ms
arte.ai-new-search.sync.idle-max=5s
```

环境变量 `ARTE_AI_NEW_SEARCH_IDLE_MAX` 可覆盖上限；范围为 `sync.tick` 到 1m。若全部已知扫描尚未到期（例如只开启周期校准，或一次回填仅剩退避任务），
无需重新扫描来证明空闲，也会逐步延长等待。每轮从持久任务队列取得最早 `next_at`，包含尚未到期的重试；等待受该时间及已知未来扫描时间约束，必要时可以短于活跃间隔。
到期积压继续按活跃间隔排空，不因退避被跳过。查询条件对应现有 `idx_search_pending` 索引，无需新增索引。

未取得租约时丢弃跨轮观察，使用活跃间隔到其两倍之间的随机等待，上限不超过 `idle-max`。初始化或扫描轮次失败按已有
`retry-initial`／`retry-max`
参数指数退避，恢复成功后重新观察。调度只在 Worker 完成并释放租约后等待，不让空闲睡眠占用数据库事务或租约；单次回填完成后仍自动停止。
摘要中的 `nextDelayMs`／`nextRunAt` 使用实际选定的等待计划。测试采用 H2 和虚拟定时器，覆盖完整轮次、积压、未来重试、变化恢复、竞争租约和停止；未实测生产
MySQL／ES。 5s 是最大空闲等待配置，不是新消息入索引的延迟承诺；实际新鲜度还受数据规模、整轮扫描耗时及 ES refresh 影响，部署后需单独测量。

## 租约校验和数据库时钟

常态下 `generation ... FOR UPDATE` 同时返回租约与数值数据库 Unix 毫秒，不再为每次租约校验单独查询时间。 worker、fence
和有效期仍逐次校验；读取结果仅用于当前短事务，不在下一次操作或 ES 请求期间复用。
[MySQL 的 CURRENT_TIMESTAMP 在语句开始时取值](https://dev.mysql.com/doc/refman/8.4/en/date-and-time-functions.html)
，所以锁等待可能让采样过旧： 客户端单调耗时仅用于判断等待是否可能跨越到期点；若租约剩余时间不大于该耗时，则在已经持锁的事务内重新读取数据库时间再判断。
不会把 JVM 墙钟或单调时钟转换成数据库 epoch，也不会仅凭保守耗时拒绝仍有效的租约。

领取／续租的 `lease_until`、失败／继续处理的 `next_at`、完成时间在各自 UPDATE 内按数据库时间计算，避免复用锁等待前的采样。
待处理任务直接在查询内比较数据库时间；调度的最早任务时间与数据库时钟通过同一 SELECT 返回，无任务时保留 null，不伪造立即到期。
批量发现、校准检查点仍在入队后取时，校准保存前仍复核租约有效期，不能因为共用事务就省略耗时入队后的校验；失效时入队和检查点一起回滚。

`SearchSyncLeaseClockTest` 使用 H2、受控 SQL 时钟投影和单调耗时验证旧采样跨过到期、仍有效租约、续租／重试写入时间、worker/fence
拒绝及校准回滚。 它不等价于真实 MySQL 锁等待测试；当前未配置隔离 MySQL／ES 测试环境，未执行真实基础设施验证。本次未连接或修改业务数据库，未重启实例。

## 失败恢复与部署约束

- 创建后登记失败、登记后别名未完成：修复依赖后自动重试，补齐缺失步骤。
- 多实例启动：同一别名对由 MySQL 命名锁串行，显式代际激活也使用同一锁。实例须共享相同物理数据源及别名配置；外部运维改别名须避开这些操作。数据库连接池至少两条，并为业务留出容量。
- 已登记代际的物理索引丢失：返回 `REGISTERED_INDEX_MISSING`，不创建同名空索引；准备全新代际并显式回填。
- 规则／字段／分析器不兼容、别名多目标或冲突：明确失败，保留现状，修正配置或走显式代际维护。
- 初始化未成功：搜索返回依赖不可用，扫描暂停，本初始化链路不阻塞普通聊天；后台按 `sync.retry-initial`（默认 2s）至
  `sync.retry-max`（默认 5m）退避重试。
- ES 权限须覆盖版本与节点插件信息读取、索引定义读取、创建索引及维护别名；MySQL 账号需能使用命名锁并读写已迁移的四表。初始化不执行表结构迁移。

部署文件中的主／初始化开关现已默认开启，可由对应环境变量覆盖；Java
配置类型的缺省值仍为关闭。查询和后台同步开关独立配置，部署默认开启；本轮未重启正式实例，代码变更和隔离验证不等于正式部署或搜索灰度完成。

## 验证入口

沿用 `scripts/verify-conversation-search-s2.sh` 的真实隔离 MySQL／ES 验证；初始化用例位于
`SearchSyncInfrastructureIntegrationTest`，开关及装配用例位于 `SearchConfigurationTest`
。真实测试使用随机数据库、索引和别名，完成后清理；测试数据源采用连接池，覆盖持锁连接与进度短事务同时使用连接的场景。

2026-10-17 最终验证：

| 范围                                        | 结果                                                    |
|---------------------------------------------|---------------------------------------------------------|
| 开关／装配、搜索契约、同步 Worker           | 41 项通过                                               |
| 真实 MySQL＋ES，使用应用同类型 Druid 连接池 | 12 项通过，含 9 项初始化／互斥用例                      |
| 查询服务、历史持久化、HTTP、候选可见性回归  | 30 项通过                                               |
| 合计                                        | 83 项通过，0 失败、0 错误、0 跳过                       |
| 应用集成                                    | JDK 21、Maven offline、arte-app 及依赖模块 compile 通过 |
| 清理核对                                    | 最终临时 ES 索引 0、临时 MySQL 测试库 0                 |

真实用例覆盖首次创建、重启不重置进度、四实例并发、登记失败重试、单侧别名补齐、保留旧活动代际、拒绝不兼容映射／分析器、拒绝冲突／多目标别名、拒绝沿用丢失索引的旧账本、查询就绪门控及后台重试、显式激活与初始化互斥。首轮非池化直连出现连接中断；改用连接池后完成
Hikari 与 Druid 复验，最终以 Druid 结果为准。

本轮只操作随机隔离测试资源，未重新执行业务 MySQL 迁移、创建正式索引或开启正式查询。故障隔离范围是新增会话搜索初始化；文章索引原有同步
`@PostConstruct` 启动策略未调整，不能据此承诺整个应用在 ES 完全不可用时仍能启动。

后续部署配置默认启用调整：已核对两个 profile 文件与 Maven 生成的运行时资源一致，10 项配置／装配测试通过；本次配置调整未重新执行上述全部
83 项测试，未重启正式实例。
