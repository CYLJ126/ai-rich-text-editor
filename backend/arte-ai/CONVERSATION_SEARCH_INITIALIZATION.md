# 会话搜索启动初始化

MySQL 四表迁移需先执行；应用不执行 SQL DDL。用户已确认执行该脚本，本轮未复核业务库。正式 ES 索引、业务回填及查询未在本轮自动启用。

## 首次初始化

`backend/profile/app.properties` 和 `.example` 已默认开启搜索主开关及启动初始化，默认物理代际为
`conversation-search-v2-g01`；修改后需重新构建资源并启动应用。查询／扫描开关保持 false。以下环境变量可覆盖部署配置：

```bash
export ARTE_AI_NEW_SEARCH_ENABLED=true
export ARTE_AI_NEW_SEARCH_INITIALIZE_ON_STARTUP=true
export ARTE_AI_NEW_SEARCH_GENERATION=conversation-search-v2-g01
```

AI 主开关 `arte.ai-new.enabled` 也须已开启；依赖使用现有的 ElasticsearchClient、RedissonClient 和配置指定的物理数据源。Java
配置类型不自动生成代际名，部署模板已指定上述默认物理名，可按环境覆盖；已有代际名不得当作丢失索引的重建名称。

对应配置：

```properties
arte.ai-new-search.enabled=true
arte.ai-new-search.initialize-on-startup=true
arte.ai-new-search.sync.generation=conversation-search-v2-g01
arte.ai-new-search.query-enabled=false
arte.ai-new-search.scan-enabled=false
arte.ai-new-search.backfill-enabled=false
arte.ai-new-search.reconcile-enabled=false
```

ApplicationReady 后后台依次核验／创建索引、登记 MySQL 代际和检查点、补齐别名。成功日志为
`AI search initialization ready generation=...`；这表示索引可用，不表示历史回填已完成。索引定义唯一来源为
`SearchIndexSchema.create`，无需另行执行 ES JSON 脚本。初始化自身不写入会话正文。

默认读别名 `conversation-search-read`，写别名 `conversation-search-write`
；首次绑定到指定物理索引。重启只核验兼容性，不改映射、不重置扫描进度、不替换已有别名、不删除旧代际。ES／IK 默认版本均为 9.4.2，规则
v2；部署应按实际固定版本配置。副本数可由运维调整，不作为自动改写或重建依据。

## 初始化后

需要持续同步时按部署配置开启 `scan-enabled=true` 和 `reconcile-enabled=true`；如仅做一次历史回填，可单独开启
`backfill-enabled=true`，完成后自动停止。初始化先完成，扫描才启动。查询开关单独控制，开放前按 S5
部署交接验证灰度范围、权限及新鲜度；搜索不等待全历史回填。

已有活动别名时，如果 `sync.generation` 指向另一个代际，该目标必须已显式创建且兼容。初始化只核验和登记目标，保持读／写别名原指向。升级与回退仍使用
`ConversationSearchMaintenance.create / prepareActivation / activate` 流程，不以重启替代校准和切换。

## 失败恢复与部署约束

- 创建后登记失败、登记后别名未完成：修复依赖后自动重试，补齐缺失步骤。
- 多实例启动：同一别名对由 MySQL 命名锁串行，显式代际激活也使用同一锁。实例须共享相同物理数据源及别名配置；外部运维改别名须避开这些操作。数据库连接池至少两条，并为业务留出容量。
- 已登记代际的物理索引丢失：返回 `REGISTERED_INDEX_MISSING`，不创建同名空索引；准备全新代际并显式回填。
- 规则／字段／分析器不兼容、别名多目标或冲突：明确失败，保留现状，修正配置或走显式代际维护。
- 初始化未成功：搜索返回依赖不可用，扫描暂停，本初始化链路不阻塞普通聊天；后台按 `sync.retry-initial`（默认 2s）至
  `sync.retry-max`（默认 5m）退避重试。
- ES 权限须覆盖版本与节点插件信息读取、索引定义读取、创建索引及维护别名；MySQL 账号需能使用命名锁并读写已迁移的四表。初始化不执行表结构迁移。

部署文件中的主／初始化开关现已默认开启，可由对应环境变量覆盖；Java
配置类型的缺省值仍为关闭。查询和扫描开关保持独立关闭；本轮未重启正式实例，代码变更和隔离验证不等于正式部署或搜索灰度完成。

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
