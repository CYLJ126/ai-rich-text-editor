# 第 4 步：单次 DeepSeek 文本交互

已实现受控 WebClient／Reactor Netty HTTP 客户端、Spring SSE codec、DeepSeek 专有 DTO 与平台生成信号映射。新链路不经过 Spring AI 的 DeepSeekChatModel、DeepSeekApi、ChatClient 或 Advisor；不增加框架自动重试、工具执行或记忆注入。

本步验收范围是一次内部 `ModelGateway.generate` 订阅。`ChatService.submit` 仍只可靠受理；Worker、`InvocationCoordinator.dispatch`、Attempt／预算生命周期及终态提交现已由 [第 5～6 步](EXECUTION_IMPLEMENTATION.md) 接入并使用独立开关。只开启本步配置不会自动发送已受理请求。

## 实现分工

| 实现 | 职责 |
|---|---|
| `DefaultModelGateway` | 重验能力、绑定和连接，管理一次连接借用，落实绝对期限及本地取消 |
| `DeepSeekGenerationProviderAdapter` | 文本请求映射、供应商 JSON 校验、有界聚合，输出 Delta／Result／Failure |
| `ChatCompletionsSseProtocolAdapter` | 单次 POST、HTTP 状态及 Content-Type 校验、SSE 解码、保留 `[DONE]` |
| `HttpConnectionRuntime` | 独立有界连接池、当前连接授权、出口与 DNS 校验、发送时解析凭据、释放及失效 |
| `EnvironmentCredentialResolver` | 已登记 SecretRef 到环境变量名称的映射；可替换为 Vault／KMS 等实现 |

通用 Gateway 和协议端口保留泛型。供应商 DTO 位于 `infrastructure.provider.deepseek`，连接句柄和凭据不进入持久化快照或结果接口。旧 `com.arte.ai` 的配置、客户端及 MyBatis 拦截器没有改动。

## 启用

参考 [完整配置示例](examples/ainew-generation.yml)，它包含前 3 步所需配置，适合替换原 admission 示例后显式导入。不要把两份示例中的列表简单叠加。先按实际主体、预算、费率、模型容量调整示例值；示例费率不是供应商价格。

- 前 3 步开关仍为 `arte.ai-new.enabled=true`；本步独立开关为 `arte.ai-new-generation.enabled=true`，默认关闭。
- 连接 `provider-id=deepseek`，协议引用为 `protocol/chat-completions-sse/v1`，绑定模型为所需远端模型；首批采用 `deepseek-chat`，请求显式发送 `thinking.type=disabled`。
- `endpoint` 是 base URL，如 `https://api.deepseek.com/v1`；协议在其后添加 `/chat/completions`。
- `allowed-origins` 仅填写精确 scheme／host／port，如 `https://api.deepseek.com`，不含 `/v1`。仅本地测试允许显式登记 HTTP 回环 origin。
- `secrets` 保存 SecretRef 和环境变量**名称**，如 `ARTE_DEEPSEEK_API_KEY`；实际值通过部署环境注入，在发送时解析。缺失密钥会拒绝发送，不在启动时读取密钥值或连接供应商。环境变量轮换通常需要重启进程；在线轮换可替换凭据解析器。
- 池、单池连接数、待领取数量、领取超时、SSE 帧和请求字节都有独立上限。连接的 `max-response-bytes` 限制整个响应（包括心跳和协议开销），调用的 `maxOutputBytes` 限制文本 UTF-8 输出；这些上限含义不同。
- 启动仅构造组件，不连接数据库、不执行 DDL、不初始化预算、不启动 Worker。启用后缺少匹配的 DeepSeek SSE 绑定会启动失败；未知配置字段也会失败。

## 交互及结束规则

首批支持 SYSTEM／USER／ASSISTANT 文本和 `TextOutput`。请求固定 `stream=true`、`stream_options.include_usage=true`，映射 maxOutputTokens、temperature、topP 和 stop。工具、结构化输出、媒体及 reasoning 内容不在本步范围；收到未支持的供应商输出会明确失败，不静默丢弃。

每次订阅独立聚合，输出契约为 `Delta* → Result 或 Failure → onComplete`。没有内部 `subscribe`／`block`／`retry`；重复订阅会发起另一次交互，业务幂等必须由 Coordinator 和耐久派发保证。

- 空格、换行是有效 TextDelta，保持原样；空增量不发布。
- `finish_reason` 仅是结束事实。必须同时收到 `[DONE]`，才形成结果；EOF、响应解码失败或断连均不能当成功。
- `stop` 且有有效文本时 `complete=true`；`length`、`content_filter` 或未识别的结束原因返回 `complete=false` 的结果，后续由 Coordinator 决定 Invocation 终态。
- 缺少供应商用量时保留 `Usage.UNKNOWN`；不通过文本估算或补零冒充计费用量。部分用量字段保持 null，已报告零值保持零；校验总数和一致性。
- 断流、超时、显式本地取消及非法后续响应返回安全 Failure，保留已经聚合的部分文本与已知用量。远端可能执行时使用 `POSSIBLE／UNKNOWN`，不因连接关闭推断零费用或允许重发。
- HTTP 明确拒绝和其他供应商故障分别分类；`retryable` 只是事实描述，Gateway 自身不重试；GenerationDispatcher
  只对已知无副作用且没有输出的瞬时失败创建新 Attempt。错误对象不包含请求、响应正文、API Key 或底层异常消息。

绝对期限不会因增量到达而重置。运行取消、超时和下游取消都会取消 HTTP 订阅并释放实例内借用；取消订阅本身不修改耐久取消记录，也不保证供应商停止计费。

## 连接边界

连接池按连接定义版本、主体／空间和校验后的地址隔离，数量有界，忙池不被静默淘汰。DNS 阻塞解析进入专用有界调度器，HTTP 收发使用独立事件循环；不占用持久化事务线程，不处置旧 AI 或全局 Netty 资源。

发送前重新解析连接及权限，解析当前 SecretRef。目标 origin 使用精确白名单，DNS 全部结果通过校验后固定给 Netty；拒绝私网、回环等不适用地址（显式本地测试除外），禁止重定向和 Netty 自动重发。每个 Lease 只允许一次 HTTP 请求，释放幂等，失效会撤销当前 owner 对该连接的已有句柄。

`invalidate` 是当前主体的实例内池失效操作，不是跨实例配置停用广播。后续动态控制面仍需把配置刷新、连接版本变更和跨实例通知落实为实际实现。

## 测试与下一步

新增测试使用临时 127.0.0.1 HTTP 服务，实际经过 WebClient、Netty 和 Spring SSE codec。覆盖请求字段、多行 SSE／心跳、中文与空白、用量、缺少 DONE、非法 JSON／身份变化、HTTP 拒绝、重定向、字节上限、取消／期限、连接释放及容量，以及属性绑定和启动零外部交互。测试不使用真实 API Key，不访问真实 DeepSeek 或当前数据库。

本次新增 23 项测试，连同前 3 步、契约、持久化及拦截器回归，共 132 项通过；`arte-app` 编译通过。SSE 解码前按块转换为有界堆缓冲区，规避当前 Spring 7 字符串 codec 在超限路径对拆分池化缓冲区的重复释放；未聚合整个响应到内存。

从项目根目录运行相关回归：

```bash
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider \
  '-Dtest=com.arte.ainew.admission.*Test,com.arte.ainew.persistence.*Test,com.arte.ainew.contract.*Test,com.arte.ainew.context.*Test,com.arte.core.interceptor.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Worker／dispatch 已接入，配置及完整测试入口见 [异步执行](EXECUTION_IMPLEMENTATION.md)：核验 Outbox 和 Attempt 的租约、fencing／版本，原子预留预算及提交发送事实，再构造 GatewayCall；保存输出、结果及终态，已知费用结算，未知费用待对账。入口不能为了测试绕过这些步骤直接调用 Gateway；GatewayCall 的结构校验不能证明数据库提交、预算预留或有效租约。
