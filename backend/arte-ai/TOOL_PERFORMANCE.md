# AI TOOL 分阶段观测与基线（P01）

日期：2026-10-19。P01 已增加测量及复测工具；P02 已减少重复授权读取，见 §7；P03 已合并固定材料读取并复用编译计划，见 §8。 P04
已减少受理事务往返，见 §9；P05 已优化 Worker 领取和许可校验，见 §10；P06—P07 仍待分别实施。授权规则、事务边界、5
秒工具策略、调度容量、固定版本及 manifest 均未调整；没有数据库增量脚本。

## 1. 日志入口

重新编译 arte-core／arte-ai／arte-app 并重启后端，发起新的工具调用。沿用现有
`arte.observability.ai-http.enabled=true`（默认开启）接入 JDBC、Reactor 和持久化调度器；无需新增业务配置。
`com.arte.core.diagnostics.OperationObservation` 的 INFO 日志须可见。关闭观测配置或使用未接入过滤器的数据源时，
阶段耗时仍可能出现，但不能据其零计数断言没有 SQL。

| 日志            | 作用                                                                |
|-----------------|---------------------------------------------------------------------|
| AI_HTTP_METRICS | 原 HTTP 总耗时、SQL、连接获取、入口授权和调度器等待统计，口径保持   |
| AI_TOOL_STAGE   | 每次订阅／同步阶段的耗时、SQL、剩余期限、完成信号；后台执行也可采集 |
| AI_TOOL_POINT   | 物理提交／回滚以及 Worker 已读出记录等生命周期点                    |

通过 invocationId、traceId 关联。新请求重放原调用时，受理结束阶段关联真正胜出的原 Invocation，同时保留
requestedInvocationId； 事务子阶段及提交点沿用本次请求 trace，不把重放计作新调用。nodeId 是进程启动时生成的随机诊断标识，不是持久化权威。
没有输入、配置、幂等键、凭据、SQL 参数或原始异常消息；错误只记录类型。 阶段数量由固定流程界定，不对每次轮询、心跳或单条 SQL 输出
INFO。

## 2. 阶段含义

| 阶段                                        | 测量范围                                                       |
|---------------------------------------------|----------------------------------------------------------------|
| ENTRY_AUTHORIZATION                         | HTTP 工厂解析当前入口身份／授权，使用入口请求期限              |
| ADMISSION                                   | 进入 ToolAdmissionService 到受理回执／错误；包含以下受理子阶段 |
| REPLAY_LOOKUP                               | 按原幂等身份查找已有受理                                       |
| BINDING_RESOLUTION                          | 当前绑定／发布状态及固定材料解析                               |
| SCHEMA_VALIDATION                           | 读取 Schema、参数与配置校验、编译验证                          |
| HOST_AUTHORIZATION                          | 宿主明确的当前免费只读与资源资格预检                           |
| ADMISSION_COMMIT                            | 等待存储调度器、编码快照及受理事务的总等待                     |
| ADMISSION_TRANSACTION                       | JDBC 工作线程中的真实事务，包含取得连接、提交／回滚及事务回调  |
| COMMIT_AUTHORIZATION                        | 受理物理事务内的最终当前资格检查                               |
| COMMIT_GOVERNANCE                           | 当前绑定／发布、实际选版及目录材料复核                         |
| COMMIT_WRITES                               | 通用受理、队列容量、快照、事件和 Outbox 写入及最终期限检查     |
| WORKER_LOAD                                 | Worker 首次读取权威 Invocation，开始时间位于首次读取之前       |
| DISPATCH_ROUTING                            | 协调器按权威 Invocation.kind 路由前的记录读取                  |
| DISPATCH／DISPATCH_LOAD                     | TOOL 派发总流程／领取资格及记录读取                            |
| PREFLIGHT                                   | 执行前快照、当前绑定、宿主资格、Schema 及原期限检查            |
| PREPARE_DISPATCH                            | 同库原子准备 Attempt、领取／容量许可和 FREE 派发资格           |
| GATEWAY／GATEWAY_BINDING／GATEWAY_PREFLIGHT | 网关总流程／固定方案读取／材料、授权及治理复核                 |
| ADAPTER                                     | 订阅实际本地适配器至其返回，包含其调度等待；不含后续输出校验   |
| OUTPUT_VALIDATION                           | 输出 Schema、身份、副作用及字节上限检查                        |
| RESULT_PERSIST                              | 保存工具结果；因字节上限明确跳过结果保存时不存在该阶段         |
| TERMINAL_COMMIT                             | 正常结果或执行前拒绝的耐久终态提交                             |
| RECOVERY                                    | 重领后的已有结果／原派发事实恢复，不重新执行工具               |

ENTRY_AUTHORIZATION 在真实 TOOL 提交／预览的 HTTP 上下文存在时采集。Worker 首次读取失败时尚未得到原 traceId／deadline， 该条
WORKER_LOAD 可只有 invocationId。预览不产生 ADMISSION 或提交点。

elapsedMs 使用单调时钟；epochMs 与剩余期限使用系统 UTC 墙钟，只用于诊断，不额外调用注入的业务 Clock。
选版后的有效期限会收紧同次调用父阶段的诊断值；不修改执行上下文或延长 deadline。历史重放保留历史期限。

## 3. 统计边界

- **父阶段包含子阶段**，SQL 与耗时不能把父子相加。比较入口 SQL 与受理 SQL 可以对账 HTTP；生产 JWT 过滤等未归入这两阶段的
  SQL 会体现在脚本的 unassignedSqlCount。本机独立 MVC 验证该差额为 0，不代表生产一定为 0。
- sqlCount 为开始执行的 JDBC 调用次数，批处理计一次 JDBC 执行；sqlCompletedCount 为完成数，sqlInFlight 为阶段结算时在途数。
  jdbcObserved=false 只表示没有捕获 JDBC 执行事件，不能证明没有访问数据库。
- forUpdateExecuteMs 包含 FOR UPDATE／FOR SHARE 语句的网络、执行及可能的锁等待； **lockWaitMs 保持 null**。 upsert
  也可能等待锁，其耗时仍计入 sqlExecuteMs。需要真实锁等待证据时，用记录中的 mysqlConnectionIds 关联 MySQL
  performance_schema 当前锁等待及线程事实；不为诊断额外查询 CONNECTION_ID ()。阶段最多记录 64 个连接 ID，截断显式标记。
- SQL 累计执行耗时在并行子操作下可超过阶段墙钟耗时；不能把 elapsedMs − sqlExecuteMs 直接解释成 CPU 时间或锁等待。
- SUCCESS／EMPTY 是 Publisher 或同步函数的结束信号，不表示工具业务成功；最终执行结果以 Invocation 权威状态为准。
  CANCELLED／sqlInFlight>0 是部分统计，应继续查 ADMISSION_TRANSACTION 和物理事务点。
- ADMISSION_COMMITTED 在数据库提交成功、既有提交后唤醒回调之前记录；同键重放为 ADMISSION_REPLAY_COMMITTED，回滚为
  ADMISSION_ROLLED_BACK，事务最终状态不明为 ADMISSION_OUTCOME_UNKNOWN。HTTP 超时不代替这些事实。
- 提交到 Worker 首次读取的间隔采用 `WORKER_LOAD.startedEpochMs − ADMISSION_COMMITTED.epochMs`，包含恢复扫描、容量、领取事务和调度等待，
  不是纯轮询间隔。脚本默认只计算同 nodeId 的配对；跨节点须先校准墙钟再显式启用，负时间配对不会被改成 0。
- 后台领取／执行清除来源请求的诊断归属，保留其他既有 Reactor 上下文，避免提交后同步唤醒把后台 SQL 算入原 HTTP。

## 4. 业务环境复测

先重启加载本次代码，再通过页面正常预览并调用。单次追踪：

```sh
python3 backend/arte-ai/scripts/measure_tool_phases.py backend/log/info.log \
  --trace-id 实际追踪号 --output /tmp/tool-one-trace.json
```

连续基线使用独立的本轮日志文件；先预热 10 次，再保留至少 50 次新调用，输入、空间、并发方式和数据库网络路径保持一致：

```sh
python3 backend/arte-ai/scripts/measure_tool_phases.py /tmp/本轮工具日志.log \
  --skip-invocations 10 --output /tmp/tool-baseline.json
```

脚本只读文件，不访问数据库、不发送调用。输出每阶段 P50／P95／P99、SQL、剩余期限、完成信号分布、在途／未观测样本、阶段覆盖及提交到
Worker 间隔。 预览单独排除，重复日志去重；未到受理服务的入口失败单列，未到达适配器、缺少配对显式保留缺失计数，不能把失败样本删掉后报告成功率。
默认不计算跨进程间隔；只有已确认节点时钟校准时使用 `--allow-cross-node-clock`。本轮日志范围应覆盖请求起点至事务／Worker
最终结束。

## 5. 本机 P01 基线

原始统计：[p01-local-baseline.json](acceptance/p01-local-baseline.json)。独立 Docker MySQL 8.4.11、REPEATABLE-READ、 buffer
pool 128 MiB、flush_log_at_trx_commit=1；JDK 21.0.8，Druid 最大 8 个连接。正式 Spring／MVC／当前平台授权和真实文本工具， **自动
Worker** 使用现有提交唤醒及恢复扫描，10 次预热＋50 次串行样本；50 次均 HTTP 202 并最终 SUCCEEDED，零费用预留。

| 路径                       | P50 ms |  P95 ms |  P99 ms |
|----------------------------|-------:|--------:|--------:|
| HTTP 受理总耗时            | 46.254 | 240.387 | 254.028 |
| 入口授权                   |  5.029 |  13.346 |  19.494 |
| 受理服务                   | 39.901 | 229.529 | 232.481 |
| 提交到 Worker 首次读取开始 |     11 |      63 |      94 |
| 执行前预检                 | 13.755 |  75.747 | 118.448 |
| 原子准备派发               | 14.525 |  63.811 | 156.786 |
| 网关预检                   | 10.242 |  45.406 | 115.354 |
| 本地适配器                 |  0.074 |   0.186 |   0.208 |
| 结果保存                   |  3.816 |  28.023 |  46.896 |
| 终态提交                   | 10.184 |  44.049 |  96.905 |

预热后 HTTP 每次 102 条 JDBC SQL，其中入口 11 条、受理服务 91 条；宿主预检 20 条、最终事务资格检查 18 条。
这些阶段说明授权与持久化往返仍然较多，可作为 P02—P05 的定位依据。102 与业务日志单次 107 的环境／预热状态不同， **不能解释为本次
P01 减少了业务 SQL**。

本机不含生产 JWT 过滤、浏览器网络、业务数据库 RTT，也不是参考规模持续并发或双应用节点验收；不能把该延迟作为业务环境已经修复的证据。
业务环境须按 §4 重新采集，正式性能门槛沿用需求文档。

复现：JDK 21，端口替换为一次性 MySQL 映射。只允许指向 root／platform-test 的隔离测试实例，测试创建并清理随机 platform_test_*
库：

```sh
mvn -o -f backend/pom.xml -pl arte-app -am -Pdev,workspace-baseline \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider \
  '-Dtest=OperationObservationTest,ToolPhaseObservationIntegrationTest' \
  '-Dplatform.test.mysql=jdbc:mysql://127.0.0.1:临时端口/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
  -Dplatform.tool.p01.baseline=true -Dsurefire.failIfNoSpecifiedTests=false test
python3 backend/arte-ai/scripts/measure_tool_phases.py \
  backend/arte-app/target/tool-p01-stage-events.log --output /tmp/tool-p01-baseline.json
```

测试已剔除预热，处理该文件时不用再次 skip。target/tool-p01-stage-proof.json 保存测量环境及阶段原始数据；本轮副本在
/tmp/arte-tool-p01-stage-events-mysql.log。临时容器不作为后续部署依赖。

## 6. 回归验证

选定回归共 181 项通过、零失败／错误／跳过：arte-core 5、arte-ai 162、arte-app 14；脚本 8 项通过。 覆盖真实提交／重放／回滚、超时后的迟到
JDBC、阶段上下文跨线程／定时器、后台与 HTTP 隔离、原 HTTP／SSE 指标，以及工具／生成 Worker 和分类调度。 独立 MySQL 的 50
次真实调用另外记录环境证据，不与重叠测试重复累计。没有运行全仓测试、业务环境负载或浏览器验收。

主要日志：/tmp/arte-tool-p01-regression.log、/tmp/arte-tool-p01-http-regression.log、
/tmp/arte-tool-p01-final-verification.log、/tmp/arte-tool-p01-mysql-final.log。

## 7. P02 授权读取复用与复测

### 实现范围

- `RbacIdentityProvider.duringAuthorization` 显式限定一次同步授权检查；账户、稳定身份映射及当前 RBAC 操作集合只在该批次复用。
  状态属于实际 appDataSource 事务的同步器列表，REQUIRES_NEW 不取得被挂起事务的事实；成功或异常退出即清空。
  同一外层事务随后再次授权也重新读取，不把复用范围延长到业务写入。
- 普通快照读与锁定当前读分别记录，按主体／外部账户键复用同一身份行；不保留缺失身份，不缓存允许决定。
  当前主体、会话名称／创建时间、角色状态、操作权限、空间生命周期和 AI 开关的规则保持。
  `actorCurrent` 的身份映射也使用锁定当前读，避免账户当前读与旧映射快照混用。
- 接入管理会话、工具宿主预检、最终提交复核及历史／取消资格检查。入口独立只读快照保持原实现； Worker
  的执行前检查和网关授权仍单独复核。空间事实沿用原公共门控的一批授权内复用，不把事实带入业务写回调。
- MySQL 并发用例暴露最终授权建立 RR 快照后仍用普通读定位同键受理的缺陷；提交内定位及关联 Invocation 改为锁定当前读。
  预查／历史查询保持原模式，没有增加查询次数、捕获重复键伪造回执或重试整个事务。

没有新增配置或数据库脚本，没有调整授权规则、5 秒期限、工具版本、manifest 或调度容量。重新构建并重启后端后生效。

### SQL 与本机基线

原始统计：[p02-local-baseline.json](acceptance/p02-local-baseline.json)。同样使用独立 MySQL 8.4.11、REPEATABLE-READ、 128
MiB buffer pool、flush_log_at_trx_commit=1、Druid 最大 8 连接及 JDK 21；自动 Worker，10 次预热＋50 次串行样本。 50 次 HTTP
202，最终均 SUCCEEDED，零预算预留；入口和受理阶段 SQL 与 HTTP 汇总逐次对账，差额均为 0。

| 统计范围                          | P01 每次 SQL | P02 每次 SQL |         减少 |
|-----------------------------------|-------------:|-------------:|-------------:|
| HTTP 受理汇总                     |          102 |           96 | 6（约 5.9%） |
| 入口独立授权快照                  |           11 |           11 |            0 |
| 受理服务 ADMISSION                |           91 |           85 |            6 |
| 宿主预检 HOST_AUTHORIZATION       |           20 |           16 |            4 |
| 最终事务资格 COMMIT_AUTHORIZATION |           18 |           16 |            2 |

宿主预检与最终资格是 ADMISSION 的子阶段，不能把各行相加。RBAC 直接／角色读取在一次常规调用里本来就只有一组，
该组查询仍保留；复用机制只省去本批中再次读取同一账户／映射／RBAC 事实的往返。

本轮 HTTP P50／P95／P99 为 48.389／381.903／514.596ms，提交到 Worker 首读为 12／93／107ms； 适配器为 0.078／0.192／0.276ms。P01 的
HTTP P95 为 240.387ms， **本轮没有得到稳定延迟下降的证据**， 只能确认 SQL 数减少。两次本机采样负载时刻不同，不能把尾延迟差异归因为本次修改或据此宣称业务环境超时已经解决。
P03—P05 仍需继续减少材料、持久化与调度往返，正式性能结论仍按原生产验收要求判断。

复现时沿用 §5 的 Maven 命令，将测试开关替换为 `-Dplatform.tool.p02.baseline=true`，日志及原始证据输出到
`target/tool-p02-stage-events.log`／`target/tool-p02-stage-proof.json`，再执行：

```sh
python3 backend/arte-ai/scripts/measure_tool_phases.py \
  backend/arte-app/target/tool-p02-stage-events.log --output /tmp/tool-p02-baseline.json
```

本轮 MySQL 原始日志保存在 `/tmp/arte-tool-p02-stage-events-mysql.log`，证明文件为
`/tmp/arte-tool-p02-stage-proof-mysql.json`。

### 验证边界

最终定向回归 287 项通过、零失败／错误／跳过：arte-core 69、arte-ai 115、arte-app 103， 覆盖身份／登录、公共事务门控、TOOL
受理／派发／网关、正式宿主和聊天控制面。日志：`/tmp/arte-tool-p02-verified-regression.log`。

独立 MySQL 的 9 项用例通过：50 次自动执行基线，以及完整工具执行集成，覆盖同键并发只生成一次 Invocation／Attempt、
撤权后原键／新键拒绝、账户生命周期变更、双 Worker、取消和重启恢复。授权批次另外验证普通／当前读分离、事务挂起／恢复、
异常和回滚清理、同一事务内再次授权识别撤权和空间关闭；聊天控制面也列入回归。 MySQL 日志为
`/tmp/arte-tool-p02-mysql-final.log`，与 H2 重叠的用例按环境分别列出，不叠加为独立测试数。

扩大回归发现既有 `RbacToolDefinitionScriptTest` 失败：断言增量脚本等于 RBAC 基线文件从 TOOL 标记起的整个尾段， 但该尾段还包含
S2-07 工具菜单登记内容。直接读取 HEAD 的两份脚本也不相等；本次没有修改脚本或放宽该断言，后续定向回归将其明确排除。 Mockito
在本机沙箱自附加失败的首轮测试，已通过显式测试 JVM javaagent 重新验证，不以初始化失败作为业务结论。

本轮未连接业务数据库／Redis、未调用外部模型、未重启业务应用，未做浏览器或正式参考资源持续负载验收；临时 MySQL 在验证后删除。

## 8. P03 固定材料批量读取与编译缓存

### 实现及边界

- 固定工具、提供者及去重 Schema 由 MyBatis 的一条 UNION ALL 查询取得，多个 Schema 引用也可通过一条有界 IN 查询读取。
  集合接口最多接收 128 个 Schema 引用，空集合不查询；返回顺序按去重后的请求顺序。完整材料的缺依赖、摘要损坏、索引漂移仍拒绝。
- 受理／预览复用本次绑定解析已取得的材料，重新核对精确工具、Schema 集合及发布合同摘要；不再次逐条读取 Schema。
  材料仅保留在本次订阅内，最终提交重新查询当前绑定、发布和材料。网关同样一次读取完整材料，并在实际执行前核对发布合同，
  之后仍按原流程再次复核固定绑定及宿主授权。目录、发布、绑定和当前配置不使用跨阶段资格缓存。
- 编译缓存按固定引用、由实际原文重算的摘要、INPUT／CONFIGURATION／OUTPUT 用途及全部 Limits 隔离； 原文容量及 Unicode
  仍逐次检查，失败不缓存，业务输入／输出及授权结果不缓存。计划不可变，每次实例校验使用独立错误预算。 进程内 LRU 最多 256
  项、估算权重最多 16 MiB；权重为原文 UTF-8 字节加每节点 256 字节，不是精确堆测量。
  编译在锁外进行，冷请求可重复编译，但同键只发布并返回一个成功计划；缓存访问及淘汰线程安全，不新增依赖或后台线程。

没有新增数据库脚本或配置项；不改变 5 秒策略、manifest、发布审批、当前授权、调度容量或现有生成流程。 更新后需重新构建并重启后端，本轮没有重启业务应用。

### 本机复测

原始统计：[p03-local-baseline.json](acceptance/p03-local-baseline.json)。独立 MySQL 8.4.11、REPEATABLE-READ、 128 MiB buffer
pool、flush_log_at_trx_commit=1，Druid 最大 8 连接，JDK 21；沿用正式 MVC／平台授权／真实文本工具和自动 Worker。 10 次预热＋50
次串行测量均 HTTP 202、最终 SUCCEEDED，零预算预留；每次入口与受理 SQL 之和等于 HTTP 汇总。

| 范围          | P02 每次 SQL | P03 每次 SQL |                                 变化 |
|---------------|-------------:|-------------:|-------------------------------------:|
| HTTP 受理汇总 |           96 |           85 |                    减少 11，约 11.5% |
| 绑定解析      |            8 |            4 |                   固定依赖图合并查询 |
| Schema 准备   |            3 |            0 |                   复用本次解析的材料 |
| 最终事务治理  |           13 |            9 |                 原事务内重新批量读取 |
| 网关预检      |           26 |           24 | 三次 Schema 读取改为一次完整材料查询 |

父阶段包含子阶段，不能将表中各行相加。Schema 准备的原始记录仍标记 jdbcObserved=false，统计脚本不会把未捕获 JDBC 自动解释为零
SQL；这里的零额外查询由实现路径、定向断言和 HTTP 总数对账共同验证。 首次冷提交还需插入固定解析，最终治理为 10 条；上述 SQL
对比仅使用预热后的 50 次样本。

本轮 HTTP P50／P95／P99 为 40.391／52.579／97.762ms，提交到 Worker 首读为 11／13／20ms， 适配器为 0.069／0.104／0.155ms。P02 本机
HTTP P95 为 381.903ms；两次采样时间和机器负载不同， 不能将全部差异归因为本次修改，也不代表业务环境或正式双节点持续负载已经达标。可确认的结构变化是
SQL 往返减少和编译计划复用。

复现时沿用 §5 的 Maven 命令，将开关替换为 `-Dplatform.tool.p03.baseline=true`，输出为
`target/tool-p03-stage-events.log`／`target/tool-p03-stage-proof.json`。只读汇总：

```sh
python3 backend/arte-ai/scripts/measure_tool_phases.py \
  backend/arte-app/target/tool-p03-stage-events.log --output /tmp/tool-p03-baseline.json
```

MySQL 原始阶段日志另存 `/tmp/arte-tool-p03-stage-events-mysql.log`，证明文件为
`/tmp/arte-tool-p03-stage-proof-mysql.json`。

### 验证

最终定向回归 345 项全部通过、零失败／错误／跳过：arte-ai 308、arte-app 37。 覆盖目录、绑定、Schema、发布、管理、受理、派发、网关及正式宿主，日志为
`/tmp/arte-tool-p03-final-regression.log`。 独立 MySQL 的 9 项集成用例全部通过，包含 50 次自动 Worker
基线、同键并发、撤权、账户生命周期、取消、双 Worker 和重启恢复。 日志为 `/tmp/arte-tool-p03-mysql.log`，与 H2
的重叠用例按环境分别记录，不相加为独立测试数。 新增定向用例验证批量查询实际只有一条语句、引用去重及顺序、缺失和损坏材料、预热后
Schema 内容变更、依赖索引漂移、 缓存版本／内容／用途／限制隔离、并发共享，以及条目数和权重两种淘汰边界。
既有配置变更、发布停用、兼容跟随、历史重放、授权和精确小数校验均保留。

本节记录 P03 完成时的验证，后续 P04 见 §9。本轮不连接业务数据库或 Redis、不调用外部模型，不运行全仓测试、浏览器验收或正式资源持续负载；临时
MySQL 验证后删除。

## 9. P04 受理事务往返压缩

### 实现及边界

- 四维队列容量一次锁读、一次批量预留，缺失计数器按需批量插入。全局／分类／空间／主体仍分别比较各自限额； 计数不缓存到
  JVM，不取消原全局调度锁。批量操作固定为四个内部键，不接收客户端任意 SQL 或无界集合。
- 释放与预留共用 `counter_key` 主键顺序的锁读，再执行批量递减；缺失计数器、零计数或受影响行数不符均使事务失败。
  不用忽略错误或补零绕过完整性检查，重复控制／终态仍由原持久化标志阻止二次释放。
- 仅新 TOOL 首事件的序号已知为 1，随 Invocation 插入一并初始化，直接写 ACCEPTED 事件； EVENT（序号 1）及 DISPATCH（序号
  0）Outbox 使用同一条多行 INSERT，仍有独立消息键、序号和提交后通知。 后续事件和原生成首事件路径保留。共享队列批量逻辑同时覆盖
  GENERATION，不调整公平调度、许可、领取或重试策略。
- 当前授权、幂等定位／冲突检查、调用 ID 唯一性检查和最终期限检查均保留。事件、索引、快照、Outbox、分类和队列预留共用原事务；
  任一写入失败、队列满或期限超限整体回滚，Worker 只在提交后唤醒；重放不重复预留或通知。

无需数据库增量脚本、依赖或新增配置。重新构建并重启后端后生效，本节为 P04 完成时的记录，后续 P05 见 §10。

### 本机复测

原始统计：[p04-local-baseline.json](acceptance/p04-local-baseline.json)。独立 MySQL 8.4.11、REPEATABLE-READ、 128 MiB buffer
pool、flush_log_at_trx_commit=1，Druid 最大 8 连接、JDK 21.0.8。正式 MVC／平台授权／真实文本工具， 自动 Worker 使用原提交通知及恢复扫描；10
次预热＋50 次串行测量，均 HTTP 202 并最终 SUCCEEDED，零预算预留。 每次入口 11 条与受理 65 条 SQL 对账为 HTTP 76 条，无未归属
SQL。

| 范围                  | P03 每次 SQL | P04 每次 SQL | 说明                                           |
|-----------------------|-------------:|-------------:|------------------------------------------------|
| HTTP 受理汇总         |           85 |           76 | 减少 9，约 10.6%                               |
| ADMISSION_TRANSACTION |           53 |           44 | 含当前授权、治理及写入                         |
| COMMIT_WRITES         |           26 |           17 | 主要优化范围                                   |
| 队列容量预留计数器    |            8 |            2 | 四次锁读及四次更新合并为锁读、更新各一次       |
| 首事件及两条 Outbox   |            6 |            3 | 含事件时间读取，省去序号读写并合并 Outbox 插入 |
| PREPARE_DISPATCH      |           36 |           34 | 原队列释放四次递减改为锁读、更新各一次         |

父阶段包含子阶段，不能把各行 SQL 相加。表中每次 SQL 为预热后样本；首次冷提交还会批量创建计数器及创建调度池， COMMIT_WRITES
最多为 19 条。队列预留行不含全局调度锁／池校验；首事件行包含既有数据库时钟读取，未把它改为进程时间。

本轮 HTTP P50／P95／P99 为 **42.484／332.011／365.130ms**，提交到 Worker 首读为 **12／108／148ms**， 适配器为
**0.075／0.222／0.377ms**。P03 HTTP 对应 40.391／52.579／97.762ms；本轮尾部延迟更高，
采样时点和机器负载未受控，因此没有证据认定延迟改善。可以确认的是往返减少；这些串行本机样本不证明业务数据库网络条件、
持续并发或双节点参考资源已经达标，也不意味着原业务超时已解决。

复现沿用 §5 Maven 命令，将基线开关设为 `-Dplatform.tool.p04.baseline=true`，输出为
`target/tool-p04-stage-events.log`／`target/tool-p04-stage-proof.json`；只读汇总：

```sh
python3 backend/arte-ai/scripts/measure_tool_phases.py \
  backend/arte-app/target/tool-p04-stage-events.log --output /tmp/tool-p04-baseline.json
```

本轮原始证据另存 `/tmp/arte-tool-p04-stage-events-mysql.log` 和 `/tmp/arte-tool-p04-stage-proof-mysql.json`。

### 验证

最终定向回归 242 项全部通过，零失败／错误／跳过：arte-ai 204、arte-app 38。覆盖原生成受理／事件持久化、
工具受理／派发／控制／调度和宿主权限，未运行全仓测试。日志为 `/tmp/arte-tool-p04-final-regression.log`。

独立 MySQL 的 10 项集成用例全部通过，零失败／错误／跳过；其中一个用例包含上述 50 次自动 Worker 基线。
覆盖同键并发与撤权重放拒绝、真实工具执行、账户生命周期、取消、双 Worker 和重启恢复； 新增 8 轮提交／取消并发，每轮只保留一个队列预留，最终计数归零。日志为
`/tmp/arte-tool-p04-mysql.log`。 同次运行另有 H2 的 40 项调度回归，按环境分别记录，不把重叠用例累加为独立数量。
新增定向断言覆盖首事件与后续序号连续、EVENT／DISPATCH 各一条、提交后各一次通知、重放不增量占用、
Outbox／批量容量写入故障整体回滚且无唤醒，以及零计数释放失败不留下半份终态。

本轮未连接业务数据库或 Redis、未调用外部模型、未进行浏览器或正式持续负载验收；临时 MySQL 已删除。

## 10. P05 Worker 领取与许可校验

### 实现及边界

- 领取仍持有原调度锁并核验池策略／节点清单；先读候选，空候选直接返回，省去不会参与仲裁的许可和游标读取。
  有候选时一次读取有效许可，在本事务内汇总分类／空间／提供者计数，每新增许可同步递增。
  每个候选的配额比较改为局部计数查找，避免反复遍历全池许可；这些计数在事务结束后丢弃，不是 JVM 容量权威。
- 原空间轮转、饱和空间／提供者跳过及每轮每空间最多一条保持；游标仅读一次，批次末尾一次保存最后领取空间，零领取不写。
  取锁顺序与事务边界保持，游标写入失败回滚整批领取、token 和许可。
- 领取校验直接复用已锁定 Outbox 行中的 work_class／finished，不重复普通读取元数据。 许可改为按 message_key 主键读取单条，由
  Java 精确核对消息身份、token、类别及数据库时间下的期限； 保留大小写敏感语义，避免数据库排序规则放宽比较；缺失、过期、旧 token
  或错误类别仍拒绝。 每个校验边界仍读取数据库时间和当前许可，不跨事务缓存；已完成消息仍沿用原清理规则。
- Worker／路由／TOOL 的 Invocation 读取、当前宿主授权、取消检查、派发准备中重复的最终时钟／租约检查、续租、
  ACK／defer、恢复扫描间隔和分类槽位保持；不延长 5 秒策略，不改变免费／收费、预算或 manifest 合同。

继续使用原有表列及主键／索引，无需数据库脚本、新依赖或新增配置；重新构建并重启后端后生效。

### 领取往返验证

新增 `ToolSchedulingRoundTripTest` 在独立 H2 上捕获实际 PreparedStatement 执行，验证：

| 场景 | P04 旧路径 SQL | P05 实测 SQL | 验证重点 |
|------|---------------:|------------:|----------|
| 已建池、空分类队列 | 6 | 4 | 仍检查调度池，无许可／游标读取 |
| 同类、三个空间，各领取一条 | 24 | 19 | 游标仅一次读取、一次写入 |
| 单条非终态领取校验 | 5 | 4 | 已锁 Outbox 元数据不重读，许可单主键查询 |

P04 列为按旧代码路径计算，未在本轮重跑旧版本；P05 列为实际 JDBC 断言。前两行只计算分类领取端口， 不含 Worker
外层的排队到期扫描；第三行不含随后 Invocation 读取。冷空队列还会创建调度池，额外一条 SQL。
三条领取场景都为可立即服务的新消息，没有过期／并发抢先变更等额外路径。

新增回归验证批末游标故障使所有领取回滚、游标最终位置等于最后领取空间、后续批次保持轮转， 以及许可
token／类别／期限／缺失和大小写变体的拒绝。原饱和跳过、跨类共享配额、跨实例公平性、 旧 token 的 ACK／续租拒绝及完成清理回归继续执行。

### 本机 MySQL 基线

原始统计：[p05-local-baseline.json](acceptance/p05-local-baseline.json)。独立 MySQL 8.4.11、REPEATABLE-READ、 数据库默认
utf8mb4_bin、128 MiB buffer pool、flush_log_at_trx_commit=1，Druid 最大 8 连接、JDK 21.0.8。 正式 MVC／平台授权／真实文本工具及自动
Worker；10 次预热＋50 次串行样本均 HTTP 202 并最终 SUCCEEDED， 零预算预留，HTTP 与入口／受理 SQL 逐次对账，无未归属 SQL。

| 范围 | P04 每次 SQL | P05 每次 SQL |
|------|------------:|------------:|
| HTTP 受理 | 76 | 76 |
| COMMIT_WRITES | 17 | 17 |
| DISPATCH_LOAD | 6 | 5 |
| PREPARE_DISPATCH | 34 | 32 |

父阶段包含子阶段，不能相加。DISPATCH_LOAD 包含领取校验与随后 Invocation 读取，因此比上表单独校验多一条。
派发父阶段还可能包含取消轮询等异步操作，本轮 DISPATCH SQL 的 P50／P95／P99 为 125／125／127， 不能把高分位的额外查询都解释成固定路径回归。

HTTP P50／P95／P99 为 **38.658／69.439／385.479ms**；提交到 Worker 首读为 **11／23／108ms**； 适配器为 **0.072／0.134／0.299ms**
。P04 的 HTTP 对应 42.484／332.011／365.130ms、首读间隔 12／108／148ms。 本轮部分分位更低，HTTP P99
更高；采样时点及机器负载未受控，不能归因为单一修改，也不代表业务库 RTT、 双节点参考资源或持续并发验收已经达标。可以确认的是固定阶段与领取路径的往返减少。

复现沿用 §5 Maven 命令，将开关改为 `-Dplatform.tool.p05.baseline=true`，输出为
`target/tool-p05-stage-events.log`／`target/tool-p05-stage-proof.json`；只读汇总：

```sh
python3 backend/arte-ai/scripts/measure_tool_phases.py \
  backend/arte-app/target/tool-p05-stage-events.log --output /tmp/tool-p05-baseline.json
```

本轮原始证据保留在 `/tmp/arte-tool-p05-stage-events-mysql.log` 与 `/tmp/arte-tool-p05-stage-proof-mysql.json`。

### 验证范围

最终定向回归 **311 项**全部通过，零失败／错误／跳过：arte-ai 272、arte-app 39。 包含受控本机 HTTP 模型服务、原生成派发与收费／不足预算、FREE
原子派发、工具执行与恢复、调度、公平性、 资格变化、控制、续租及防重 ACK。日志为 `/tmp/arte-tool-p05-final-regression.log`。 首次受控
HTTP 用例因沙箱禁止本机监听而出现 Socket Operation not permitted；开放回环测试权限后重跑全部通过，
未调用真实外部模型，未将该环境错误当作实现失败或忽略未验用例。

独立 MySQL 的 **11 项**集成用例通过，包含 50 次自动 Worker 样本、双 Worker、同键并发、提交／取消并发、
撤权、账户生命周期、停用、恢复及大小写精确比较；与 H2 重叠的用例按环境分别记录，不相加为独立数量。 日志为
`/tmp/arte-tool-p05-mysql-final.log`。额外的大小写不敏感表列补验通过，见 `/tmp/arte-tool-p05-mysql-collation.log`；
该用例将消息键及许可类别临时列设为 utf8mb4_0900_ai_ci，仍拒绝大小写变体，普通基线库仍保持 utf8mb4_bin。

本轮未连接业务数据库或 Redis，未重启业务应用，未做浏览器、全仓或正式持续负载验收；临时 MySQL 已删除。P06—P07 待分别实施。
