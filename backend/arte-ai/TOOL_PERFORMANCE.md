# AI TOOL 分阶段观测与基线（P01）

日期：2026-10-19。P01 已增加测量及复测工具；P02—P07 的性能优化仍待分别实施。 授权规则、SQL 业务流程、5 秒工具策略、调度容量、固定版本及
manifest 均未调整；没有数据库增量脚本。

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
