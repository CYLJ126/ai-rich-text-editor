# 会话搜索 S2 交付与复验

日期：2026-10-16。范围：后台扫描、耐久检查点、索引同步版本、重试、归档清理、历史回填及代际维护。搜索 HTTP、Redis 搜索会话和页面仍留给
S3／S4；默认开关全部关闭，未部署正式搜索。

## 实现与边界

- `MybatisConversationSearchSource` 只读既有 Conversation／当前标题元数据、Turn、Invocation 和已提交 ResultRef
  对应的耐久结果。每个用户问题及所有可见候选各有固定身份，包含 partial；不使用流式预览、工具载荷、内部指令、结构化输出或附件正文。助手保留真实结果引用，生成时间无法恢复时写
  null。
- 权威表没有统一、可索引且涵盖改名和候选变化的变更序号。当前采用 C／T／I
  三条稳定主键游标循环读取、源指纹差异写入；不是按最近消息时间截断，也不新增业务更新时间或通知。每轮回到起点，所以晚提交到旧键位置的事实可以被后续轮次补回。这是有界轮询方案，尚无生产规模吞吐或新鲜度承诺。
- E 通道按已知源身份重新回查当前权威事实，强制校准 ES，覆盖长期不活跃、索引丢失及源已经不存在的记录。发现一批未出现某项不构成删除依据。每批归档资格按
  owner 合并读取，发送前刷新同批资格；适配器失败不回退为空集合。
- 仅新增四张后台表：`arte_ai_search_generation`、`arte_ai_search_checkpoint`、`arte_ai_search_work`、`arte_ai_search_entry`
  。进度与待处理身份同事务持久化，检查点越过某项后仍有重试位置。工作行保存当前观察到的归档／停止收录事实；entry
  仅存无正文身份、指纹、期望状态和单调版本，不存标题、消息正文或旧重放载荷。
- 同一物理代际使用数据库时钟租约及 fencing 串行推进。源读取发生在领取之后，分配版本、确认逐项结果、更新检查点均重新核对租约；租约丢失的旧工作不能确认进度。ES
  请求不放入数据库事务，不消费／ACK 现有执行 Outbox。
- 普通写入通过 Painless 对比 `_source.sourceRevision` 后完整替换。它与 ES
  内部版本分开，避免物理删除增加内部版本后使取消归档永久写入失败。旧版本返回失败／noop，不能当成新版本的成功；bulk
  逐项确认，部分失败留待重试。
- 业务删除／已确认失效写无正文、不可搜索墓碑。归档物理删除已知条目，并按 conversationKey 清理全部残留，包含未知历史候选；每次最多清理
  100 条，未结束持久保存继续位置并在后续 tick 推进，不把正常分页进度计为失败或施加指数退避。删除使用当前同步版本比较及
  seq_no／primary_term 条件；清理读取之后、发送删除之前再次校验租约和当前资格。在途旧 UPSERT 仍可能迟到，后续完整校准继续清理。
- 取消归档后重新抽取当前源并分配更高同步版本，旧删除不能覆盖恢复。缺失耐久结果保留明确失败与重试，不补造正文或成功确认。S3
  返回片段和打开会话前仍必须检查当前 MySQL 资格，不能把后台状态或 ES 当作授权事实。
- 状态由后台表按代际／owner 聚合，包含 NOT_READY／SCANNING／READY／DEGRADED／ERROR、积压、失败、缺失结果、账本条目及完成轮次。条目数不是可搜索命中总数；READY
  不是实时追平承诺。达到失败阈值后仍持续退避重试，不丢任务。后台最多每分钟记录摘要，日志不输出正文或原始 ES 异常。

## 迁移与当前环境

独立迁移：[arte-ai-new-search-sync-migration.sql](scripts/arte-ai-new-search-sync-migration.sql)。仅作为测试资源打包，不进入应用运行时
classpath；应用不执行 DDL。新安装的搜索后台同样需要此脚本，原有新 AI 基础 DDL没有代替它。

**项目当前配置的业务库未执行本次迁移，也未为它创建备份；业务消息、Outbox 和已有表结构未改。** 验证只在本地隔离 MySQL 8.4
容器中创建随机临时数据库，执行基础 DDL 和本次迁移后运行测试，结束删除。ES 同样只创建随机前缀的临时索引，结束按具体名称删除。

其他环境启用前：确认目标物理数据源与 UTC 设置 → 使用现有数据库备份流程保存可恢复备份 → 显式执行本迁移一次并核对四张表 →
创建新的物理代际 → 开启后台。保留备份位置和执行记录；本次未代替任何业务环境完成这些步骤。原消息主存储无需搬迁。

## 参数与启用

配置：[conversation-search.properties](src/main/resources/ainew/config/conversation-search.properties)。

| 参数                                                                      | 当前默认／语义                                                         |
|---------------------------------------------------------------------------|------------------------------------------------------------------------|
| enabled／query-enabled／scan-enabled／backfill-enabled／reconcile-enabled | 全 false，查询不随扫描开放                                             |
| sync.generation                                                           | 空；任一后台开关开启时必须填写符合 generation-prefix 的具体物理索引名  |
| sync.tick／tick-budget                                                    | 250ms／2s；预算在工作项之间检查，单项仍受 SQL、HTTP 超时和载荷上限约束 |
| sync.batch-size／work-per-tick                                            | 20／10；发现积压时暂停继续扩大新发现队列                               |
| sync.lease                                                                | 60s；数据库时钟决定领取与失效                                          |
| sync.reconcile-interval                                                   | 1h；E 校准及非连续扫描轮次间隔，实际完成周期还取决于积压和重试         |
| sync.retry-initial／retry-max／error-threshold                            | 2s／5m／10，超过阈值展示 ERROR 并继续恢复                              |

scan-enabled 进行连续源循环；backfill-enabled 单独开启时完成初次 C／T／I＋E 后停止自动循环；reconcile-enabled
开启时周期回查来源及既有账本。首次扫描均有界，扫描自身不建索引或切
alias。后续新增独立[启动初始化](CONVERSATION_SEARCH_INITIALIZATION.md)；初始化和三个扫描开关均关闭时不创建这些持久化适配器，构造适配器本身也不连接数据库。

## 重建、切换和回退

内部运维入口：`ConversationSearchMaintenance`，提供 `create`、`advance`、`status`、`prepareActivation`、`activate`，均为延迟执行的
Mono；未发布用户 HTTP。可在受控运维程序装配，或从启用后台的 Spring 上下文取得 Bean。S2 不新增管理页面。

1. 选择从未复用的具体名称，例如 `conversation-search-v1-g20261016a`；调用 `create` 创建 schema 并注册独立进度。此操作不添加／切换
   alias。
2. 用固定目标的后台维护实例扫描，或反复调用 `advance(generation)` 有界推进；期间查看状态、失败与缺失结果。未创建的索引只会失败并保留重试，不会被后台隐式创建。
3. 切换前调用 `prepareActivation(generation)`，取得一次性 epoch，并启动该代际新的 C／T／I＋E 完整校准。`advance`／后台完成至
   READY 后调用 `activate(generation, epoch)`，原子切换读写 alias；未完成、过期／重复使用的凭据会被拒绝。切换请求失败后重新准备与校准，不盲目重放旧凭据。
4. 切换后让连续后台维护新代际。每项工作始终携带具体索引名，不跟随 alias；旧代际 ACK 不计入新代际。代际名称不可删除后复用；维护操作串行安排，不并发发起跨代际切换。
5. 旧代际不自动删除，既有 PIT 继续原物理索引。S3 尚未交付 Redis 活动会话登记和到期清理，因此 S2 不执行自动旧代际回收；后续删除须覆盖
   PIT 最大有效期及清理余量并核对活动会话。
6. 回退同样先对目标代际重新准备并完成当前资格校准，再切 alias；也可关闭查询。不得删除扫描事实、消息、预算数据。无跨业务提交的全局快照，不承诺切换瞬间零漏差。

## 验证与复验

JDK 21；真实本地 MySQL 8.4、ES／IK 9.4.2、Java Client 9.4.5。

第二阶段针对性验收覆盖：后台 worker、契约／配置、独立 MyBatis 工厂、既有执行存储、会话 HTTP 和装配；真实 MySQL＋ES
用例覆盖晚提交落在旧游标后、重启、过期租约、并发旧 UPSERT／DELETE、bulk 部分失败、归档迟到写入与 207
条残留清理（含未知历史候选）、取消归档、独立代际、alias 切换及旧 PIT
延续。另验证批量归档读取、真实适配失败不降级、一次性校准凭据及单次回填。真实归档管理仍使用可替换读实现模拟，不把它计为归档产品功能交付。

不访问远端服务的回归命令（真实基础设施用例未列入）：

```sh
JAVA_HOME=/path/to/jdk-21 mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dtest=SearchSyncWorkerTest,SearchConfigurationTest,SearchContractsTest,ExecutionSqlSessionFactoryTest,MybatisExecutionPersistenceTest,ConversationHttpIntegrationTest,ConfigurationAssemblyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

真实集成复验：[verify-conversation-search-s2.sh](scripts/verify-conversation-search-s2.sh)，设置以下环境变量后执行
`bash backend/arte-ai/scripts/verify-conversation-search-s2.sh -o`：

- `JAVA_HOME` 指向 JDK 21。
- `ARTE_SEARCH_IT_ES_URL` 及可选的 ES_USERNAME／ES_PASSWORD（完整前缀为 ARTE_SEARCH_IT_）。
- `ARTE_SEARCH_S2_IT_MYSQL_URL` 必须是隔离本地实例的 `jdbc:mysql://127.0.0.1:端口/mysql?...`，以及对应
  MYSQL_USER／MYSQL_PASSWORD（完整前缀为 ARTE_SEARCH_S2_IT_），允许创建／删除随机测试数据库。

复验只操作随机 `arte_search_s2_*` 数据库及 `arte-search-s2-*`
具体临时索引，不读取项目业务凭据、不调用模型。测试资源清理属于测试本身；异常中断时先核对测试前缀、具体名称与创建时间再清理，不使用
ES 通配删除。

最终结果：120 项针对性测试全部通过（0 失败、0 错误、0 跳过），包含 2 项真实 MySQL＋ES 集成用例；arte-app 集成编译通过。临时 MySQL
数据库与 ES 索引清理后均为 0。测试报告见 arte-ai/target/surefire-reports；这不代表全仓测试、生产规模性能或真实归档管理验收。
