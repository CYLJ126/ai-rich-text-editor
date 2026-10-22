# S5-03：受管 MCP 只读工具

日期：2026-10-21。本批新增默认关闭的 `arte.mcp.health`，沿用工具目录／发布、本人绑定、预览确认、可靠受理、分类
Worker、结果查询、停止和工具聊天。 首个产品操作是远端 MCP `health` 工具，不把任意 MCP 服务、任意远端工具或 Tavily 接入宣称为已完成。

## 1. 协议与产品边界

| 项目     | 当前支持                                                                                                                        |
|----------|---------------------------------------------------------------------------------------------------------------------------------|
| 固定协议 | MCP 2025-06-18，Streamable HTTP；不自动协商其他版本或回退旧 HTTP+SSE                                                            |
| 传输     | 固定 endpoint 上的 POST，接收 JSON 或 SSE；无重定向、自动重试、断线续传／Last-Event-ID                                          |
| 握手     | initialize → notifications/initialized → tools/list → tools/call，每次 TOOL 有独立生命周期                                      |
| 会话     | 无会话服务器，或初始化分配的私有 Mcp-Session-Id；不跨用户、空间、Run、Attempt 共享；正常结束尝试 DELETE                         |
| 认证     | 当前正式装配为 NONE，不读取 Secret，不发送用户登录 Token、Cookie 或自定义 Header；401／403 明确失败，无 OAuth 自动发现／登录    |
| 工具     | 管理员审核的 health，参数仅 `{}`；只读、免费、一次 Attempt、无需审批                                                            |
| 结果     | structuredContent 必须为单字段 `{status}`，值为 UP／DOWN／OUT_OF_SERVICE／UNKNOWN；content 可为空或一段相同 JSON 的 text        |
| 未支持   | stdio／本机命令、任意 URL／参数映射、其他工具、图像／音频／资源／资源链接、文本任意拼接、异步任务、收费 MCP、写入、远端结果核对 |

JSON-RPC 通过现有 HTTP runtime 实现有界适配，不另起 SDK
的直连网络／透明重试。基础规范参考 [MCP transport](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)、[lifecycle](https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle)
和 [tools](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)。 这是一份明确的受控能力子集，不能当作完整通用
MCP 客户端。

SSE 仅接收本次请求的一个最终响应，允许有界 progress／message 通知；通知正文不存储或回传模型。 未登记的服务器请求（包括
sampling／elicitation／roots）和工具列表变更通知明确失败，不能触发本机模型、文件或其他工具。
`isError=true`、JSON-RPC error、未支持内容或未完成任务都不能变成成功。服务返回的 status=UNKNOWN 是成功查询值，与
Invocation.UNKNOWN 不同。

## 2. 固定目录与协议标记

MCP 提供方显式登记 `McpToolAdapter`，保留独立于 HTTP 执行器的注册槽。
`McpToolAdapter` 与 `HttpToolAdapter` 并列继承 `RemoteToolAdapter`，共用固定版本声明、能力读取及
`invoke(RemoteToolCall)`；Gateway 按共用远端接口执行。MCP 的 Streamable HTTP 传输保持原实现，工具接口不继承 HTTP 工具协议。
缺少已核实固定绑定的旧 `invoke(GatewayCall<ToolInvocation>)` 入口明确拒绝，不发起握手或工具调用。
`ToolDefinitionRevision.protocol` 固定 `mcp-streamable-http@2025-06-18`，与 MCP 提供方、目标连接、操作和实际实例完全匹配；不能把
MCP 塞进 HTTP 或 LOCAL 登记绕过预检。 旧无 protocol 的本地／HTTP 合同保持原义，未知协议拒绝。

发现、刷新、发布预检只读取受信代码的静态元数据，不在启动或管理事务内探测远端。 连接完整摘要固定
endpoint、协议、期限及响应上限；同版本改地址或改合同属于漂移，不能覆盖。 运行时 tools/list 只验证已经审核的 health 名称及完整
inputSchema／outputSchema，不把远端 descriptions／instructions 或 annotations 当成授权依据，也不自动新增本地工具。
默认严格比较时，工具名重复、缺失、Schema 变化、分页或超过 64 项均拒绝，须重新审核新固定版本。

远端 Schema 必须与 `McpHealthToolAdapter.definitions()` 的输入／输出标准 JSON 完全一致（对象键顺序无关，数组顺序保留），包括
`$schema` 和额外字段策略。 生产接入前应先与服务提供者确认该合同；协议连通不等于任意服务满足产品合同。

transport 新增实例级 `verifyRemoteSchema` 选项；现有 `new McpStreamableHttpTransport(runtime)` 等价于传入 `true`， 正式
health 装配保持默认严格比较。受信代码可显式选择：

```java
var transport = new McpStreamableHttpTransport(runtime, false);
```

此模式不比较远端 Schema 与传入的本地文档原文，允许远端省略 `outputSchema`，适配平台与服务端采用不同 Schema 的情况。
工具名称匹配、重复名称／分页／目录上限、握手、JSON-RPC、会话、请求与响应限制、当前授权及出口检查继续执行；
`inputSchema` 仍须是对象，`outputSchema` 若存在也须是对象。平台自己的参数、配置及输出契约继续由 Gateway／适配器校验，
远端列表不会自动成为已发布工具。该选项只由受信装配选择，没有新增 HTTP 参数或配置属性，不由客户端或远端响应切换。 本次仅提供
transport 能力，尚未把 Bing 适配器改为使用此模式，也未增加其目录、授权或聊天接线。

## 3. 授权、网络与资源

复用现有身份／RBAC／空间 AI enabled 和 aiTool:invoke，受理事务和实际执行边界再检查完整免费合同。 每条 MCP HTTP
消息重新核验当前连接资格；初始化成功不永久授权后续 tools/call。 独立 MCP runtime 使用自己的 origin 白名单、DNS
地址校验、固定解析与连接池，模型和免费 GET 路径保持原方法／认证规则。

默认最多 8 池，每池 4 连接、8 等待；获取 2 秒、连接 3 秒、响应 5 秒，产品总期限 30 秒，包含排队、握手、目录验证及调用。 请求最多 8
KiB、单 HTTP 响应累计 64 KiB（含 SSE 开销）、单交换最多 64 SSE 事件、JSON 深度 24，最终工具结果上限 4 KiB。
TLS／origin／私网及元数据地址规则沿用现有出口控制，显式 HTTP 回环只供本机测试，不增加内网豁免。

Session ID 只在该次操作内存中使用，限制可见 ASCII 和长度，不写 Invocation、结果、审计或日志。远端 instructions、原始错误、通知和
`_meta` 不进入结果或模型。 未支持的业务内容明确拒绝，不丢弃图像等内容后冒充完整成功。

## 4. 停止、恢复与费用

本地停止／超时关闭当前订阅并释放 HTTP lease，不承诺远端收到取消或已经停止；本批没有实现 notifications/cancelled 确认协议。
会话清理仅在当前期限与授权允许时尝试 DELETE，最多 500ms；服务器拒绝 DELETE、撤权、取消或期限耗尽时不延长资格强行清理，服务端必须配置会话
TTL。 不保持后台长期 GET／SSE，不持久化会话，也不为 404、断流或失败重新 initialize 后重发 tools/call。

该明确只读合同的已观察错误为 NONE／KNOWN，保存 FAILED／TIMED_OUT／CANCELLED；失租或崩溃后无法确定结果的情形仍沿用通用 UNKNOWN
恢复，不自动重执行。 恢复通过原 Attempt 的持久结果收尾，不新建 MCP 会话重做原工具。

当前 MCP 产品明确 FREE，无工具费用预留或 Allocation；仍消耗工具次数、并发与总期限。 工具聊天中的模型调用继续使用原模型账户，工具描述和返回状态引起的
Token 费用由模型承担。 S5-02 收费基础保留，但本批不允许仅给 MCP 元数据添加 price 就开放收费，真实收费服务须另行验收报价、账单与授权适配。

## 5. 配置与部署

```properties
arte.ai-new-tool-mcp.enabled=false
arte.ai-new-tool-mcp.version=v1
arte.ai-new-tool-mcp.endpoint=
arte.ai-new-tool-mcp.allowed-origins=
```

仅运维设置固定完整 endpoint（如 `https://mcp.example.com/mcp`）和精确 origin（如 `https://mcp.example.com`
）；禁止用户信息、查询串、fragment，启用但缺地址／独立白名单会启动失败。 示例不是本批实际调用服务。沿用既有 AI／TOOL／执行／Worker
开关，聊天还需模型工具能力、批准预算和 Run Worker。

1. 停止新受理并协调旧在途工作；所有可能处理新材料的 HTTP 节点、目录读者和 Worker 先升级。
2. 登记 MCP endpoint／工具版本及出口白名单，确认真实服务的协议、免费只读依据、固定 Schema、session TTL 和返回内容。
3. 离线从包含本地、HTTP、MCP 全部登记来源的 Batch 计算 `StaticToolDiscovery.manifest`，按现有分类调度规则协调池和节点
   tool-manifest；不能只开 MCP 并沿用旧清单。
4. 有权管理员刷新静态目录、审核发布，用 `{}` → `{"status":"UP"}` 等实际示例验证；用户准备本人绑定，再预览／确认调用或选入
   ChatNew。
5. 更改地址、协议或 Schema 须新固定版本、重新审核及更新完整清单。当前部署每来源只登记一个版本，切换前处理旧工作，不能假定历史待执行仍有实现。

新增 MCP 编码：tool-definition-revision v3、tool-binding-resolution v3、tool-execution-snapshot v7、包含 MCP 的
tool-chat-run-snapshot v4。 旧本地／HTTP／收费版本保持原义，新 MCP 材料拒绝降级重标。没有新增数据库表或
DDL，不重新初始化账本；关开关不表示可让旧二进制读取新材料。

### 离线计算完整部署清单

手工运行 arte-app 测试源码中的
`com.arte.app.platform.ai.ToolManifestCalculator#calculateCurrentDeploymentManifest`（JDK 21）：

```sh
mvn -f backend/pom.xml -pl arte-app -am \
  -Dtest=ToolManifestCalculator \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该类特意不使用默认 `*Test` 命名，普通测试集不会自动执行；IDE 也可以直接运行该测试方法。 它读取 Maven 过滤后的
`ainew/config/application.properties`，结合 JVM 系统属性和环境变量，按当前开关创建静态定义； 复用正式 `staticToolRegistry`
、HTTP／MCP 适配器工厂和 `StaticToolDiscovery` 校验及摘要算法。 当前 MCP 开启时同时包含 health 与
Bing，关闭的来源不参与。新增装配来源时同步维护测试内的 `metadataRegistry`。

不会启动完整应用、访问数据库、调用模型／MCP、读取凭据值或自动改动配置。控制台和
`backend/arte-app/target/tool-manifest.properties` 输出工具 ID／版本／期限、可复制的 `tool-manifest` 配置及按新清单计算的
`policy_digest`。IDE 的报告位置以控制台绝对路径为准。定义或 Schema 校验失败时测试失败，不输出新的计算结果；失败后不要使用遗留报告。

计算时使用的代码、启用来源和连接配置须与实际部署一致；外部覆盖也要给计算测试提供相同配置。计算结果不表示已发布、已建绑或
Worker 就绪；更新配置和已有数据库调度池仍按前述停受理、协调消费者及空闲检查流程执行，不直接手填策略摘要。

## 6. 验证

使用隔离 H2、MockMvc 与真实回环模拟 MCP，覆盖成功生命周期、JSON／SSE、有／无会话、Schema 漂移、错误及未支持内容、撤权、出口、限额、取消、期限、Run
恢复、免费预算隔离与新编码。 精确测试数量及验证限制见 [实施记录](TOOL_IMPLEMENTATION.md)。未连接业务数据库、真实
MCP／模型服务，未开启运行环境开关或重启后端；生产 MySQL、多节点、真实 HTTPS、浏览器及性能验收仍待执行。
