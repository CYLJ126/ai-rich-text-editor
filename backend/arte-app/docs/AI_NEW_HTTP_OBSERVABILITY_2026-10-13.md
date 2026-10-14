# 新 AI HTTP 端到端诊断与两组测量

## 当前状态

指标代码与两组测量工具已实现。用户于 2026-10-14 明确允许本次读取账号 1 的现有在线会话后，已完成真实同账号单请求和 8
并发测量：最终 360 个普通请求全部业务成功，含 SSE 的 361 个请求全部匹配 trace 与 MySQL 连接 ID。临时令牌文件已删除，独立探针已停止。

实测表、逐项证据／原因／改法及归因限制见 [2026-10-14 实测报告](AI_NEW_HTTP_MEASUREMENTS_2026-10-14.md)
。此前自动审批拒绝的凭据读取未执行，之后依照用户明确授权执行。

## 计时边界

`NewAiHttpObservationFilter` 在 Servlet 请求入口创建观察对象，先于身份认证和 Controller；异步请求到 ASYNC 完成才结束。首次授权尚未创建
ExecutionContext 时已经可以计数、计时。一次请求的所有派发共用观察对象，输出一次 `AI_HTTP_METRICS`，响应返回服务端生成的
`X-Arte-Trace-Id`。

`NewAiHttpContext` 将同一个 traceId 交给 `ExecutionContextFactory`。Factory 单独记录初次授权，含其等待调度、取得连接和读取数据库所耗时间。Reactor
信号及 `ObservedScheduler` 只传播诊断对象和 MDC trace，不传播 Authentication、UserContext、认证头或输入内容。SSE 响应体在
Controller 返回后另行订阅，也显式保留此诊断上下文，重新授权仍重新执行。

原会话 Service 的 `elapsedMs` 继续表示其操作自身的耗时，不包含此前 Factory 的授权，不能与浏览器 HTTP 耗时直接等同。
`InvocationTiming` 的阶段日志也不能替代完整请求指标。

| 指标                               | 定义与限制                                                                                                              |
|------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| 客户端 `ttfbMs`                    | curl `time_starttransfer`，从客户端开始请求到首个响应字节，含连接、传输和服务端等待                                     |
| 客户端 `totalMs`                   | curl `time_total`，到完整响应下载结束；与服务器 ASYNC 完成时刻分开记录                                                  |
| `mvcTotalMs`                       | Servlet Filter 入口到请求／ASYNC 完成，覆盖工厂初次授权及响应写出；不包含进入 Filter 之前的 Tomcat 接入排队、网络或代理 |
| `serverFirstWriteMs`               | 到服务器首次成功写出响应或提交响应；不能直接称为客户端 TTFB                                                             |
| `initialAuthorizationMs/Count`     | 初次 Factory 授权的完整阶段，包含其 SQL、排队和连接获取                                                                 |
| `sqlCount`                         | 实际 JDBC execute 调用次数，含 RBAC、直接 JDBC 身份读取、公共授权、AI 存储及数据库时钟；不是 MyBatis 准备日志数量       |
| `sqlExecuteMs`                     | 已完成 JDBC execute 的累计耗时，含驱动、网络和数据库等待；不是纯 MySQL CPU 时间，亦不包含 JDBC 结果解码和业务 JSON 解码 |
| `sqlCompletedCount/sqlInFlight`    | 已完成及请求结束时仍在执行的 SQL；超时不能把在途 SQL 假装成零次或已完成                                                 |
| `connectionAcquireMs/Count/Errors` | Druid 连接取得的累计耗时、次数与失败数，包含池等待和必要的物理连接建立；保留原 DataSource 身份，不改变事务资源绑定      |
| `schedulerQueues`                  | AI／公共持久化调度器的任务数、排队累计／最大时间；计划延迟从等待中扣除，不把定时器间隔算成线程排队                      |
| `forUpdateExecuteMs`               | 含 `FOR UPDATE` 的 JDBC 执行总时间；含执行及网络，不能命名为锁等待                                                      |
| `lockWaitMs`                       | 请求日志保留 null，精确锁等待没有数据时不填 0。MySQL 锁采样工具另行记录组级 InnoDB 计数器差值                           |
| `mysqlConnectionIds`               | 来自驱动的连接 ID，用于关联 MySQL 等待采样；不为诊断额外执行 `CONNECTION_ID()`                                          |

SQL／连接／排队为累计量；授权是包含这些阶段的墙钟区间，不能直接将所有指标相加或相减来分摊 HTTP 总耗时。日志不保存 SQL
参数、聊天正文、结果、登录身份对象或令牌。

## 已取得的验证证据

- 编译通过。新增 MVC／Druid／调度集成测试覆盖初次授权 SQL、定时器跨线程 trace、线程上下文清理、调度排队、SQL 执行失败、超时在途
  SQL，以及 Controller 返回后另行订阅的 SSE。6 项通过，含 Druid 连接包装器取得 MySQL ID 的回归验证。
- ContextFactory 与只读授权快照 9 项通过；调用 HTTP 集成测试 35 项中 32 项通过，3 项 Redis 测试因未配置独立测试 Redis 而跳过。
- ASYNC 包装器的回归断言确认 32 字节响应只计 32 字节，重复派发只输出一次统计；SSE 的初次授权及两个后续数据库读取都保持同一
  trace。
- 独立本地探针在 12637 完成烟测后已停止，使用项目配置，关闭调用派发及公共 Outbox Worker；原 8080／12636 服务未重启。无凭据烟测确认响应
  trace 和 Servlet 指标生成。该请求未进入工厂授权， **不作为同账号性能样本**。随后已用最新诊断版本完成真实同账号测量，详见实测报告。
- 数据库只读检查：MySQL `8.0.22-cynos`，`@@performance_schema=false`，Performance Schema 查询返回 SQLState `42000`。全局
  InnoDB 锁等待计数器可以读取。未启用任何数据库性能开关。

## 两组测量工具

`backend/arte-app/scripts/measure_ai_http.py`：只允许 loopback 地址；认证头从权限为 600
的文件读取，不在命令行和输出暴露。使用相同账号、scope、会话版本及资源参数，第一组每个接口顺序执行，第二组每个接口并发执行（默认
8 并发、每组 5 轮）。每组的单次预热不计入样本。默认覆盖配置、预算、会话、轮次、调用状态／结果／批量快照的现有只读资源；SSE 可独立采样。

工具保存客户端 TTFB／总耗时／连接时间／下载字节、HTTP 状态、业务成功标记和 traceId；自动关联完整服务器指标，汇总样本数、业务成功数、trace
匹配数、median/p95。 **HTTP 200 但业务失败不会计为成功**。临时响应内容随请求结束删除。必须使用新的输出目录，避免旧阶段标记污染组间锁等待边界。

`backend/arte-app/scripts/AiMysqlLockSampler.java`：使用项目数据库配置做只读采样，以阶段标记／应答保证 SQL
计数器读取发生在组开始之前、组结束之后。记录全局 `Innodb_row_lock_time`（毫秒）、`Innodb_row_lock_waits` 的差值，并尝试采样
`information_schema.innodb_trx` 中的等待连接 ID。两组共用数据库；全局差值也包含其他实例的同期活动，不能未经连接／时间关联就归为本账号独有的等待。

示例（认证文件须经明确授权或标准登录准备）：

```sh
python3 backend/arte-app/scripts/measure_ai_http.py \
  --base-url http://127.0.0.1:12637/arte \
  --auth-header-file /private/tmp/arte-http-measurement-auth.header \
  --tenant-id d10c8938-a093-487a-a17f-60e3559344a2 \
  --workspace-id 1430a4bc-a260-446b-9eec-46242ed12f62 \
  --conversation-id d3434b9f-b5eb-4d6e-9192-6ca22518e0fd \
  --repetitions 5 --concurrency 8 \
  --log-file /private/tmp/arte-http-probe-logs/info.log \
  --output-dir /private/tmp/arte-http-measurement-run
```

运行数据库采样工具后，为 HTTP 工具加 `--lock-sampler`。Performance Schema 关闭时，精确的逐请求锁等待继续标注不可用。独立探针直连测量不包含前端
8080 代理耗时；需要复测完整浏览器链路时，部署同一诊断版本并将 base URL 指向 8080。

可通过 `arte.observability.ai-http.enabled=false` 关闭诊断基础设施。该配置使用独立前缀，避免违反 `arte.ai-new` 的严格配置绑定。
