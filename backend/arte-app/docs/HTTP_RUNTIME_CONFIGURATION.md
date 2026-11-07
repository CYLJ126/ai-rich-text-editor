# 新 AI HTTP 资源配置

模型、HTTP 工具和 MCP 工具统一使用 `HttpRuntimeOptions` 参数结构，通过 `NewAiHttpProperties`
绑定三个独立参数组。每类运行时继续持有自己的连接池、事件循环、DNS 调度器、凭据解析器和出口策略。

## 部署参数

| 参数后缀               | `arte.ai-new-http.model` | `arte.ai-new-http.tool-http` | `arte.ai-new-http.tool-mcp` | 含义                             |
|------------------------|-------------------------:|-----------------------------:|----------------------------:|----------------------------------|
| `max-pools`            |                       32 |                            8 |                           8 | 每个运行时保留的连接池数上限     |
| `connections-per-pool` |                        8 |                            4 |                           4 | 每池最大连接数                   |
| `pending-acquires`     |                       32 |                            8 |                           8 | 每池等待连接的请求数上限         |
| `acquire-timeout`      |                       5s |                           2s |                          2s | DNS 解析及等待连接分别适用的超时 |
| `max-in-memory-bytes`  |                    65536 |                        65536 |                       65536 | HTTP 客户端解码缓冲上限          |

参数组可分别覆盖任意字段，未设置的字段使用该组自己的默认值。例如只设置 `arte.ai-new-http.tool-http.connections-per-pool=3`
，其池数仍为 8、获取超时仍为 2 秒，模型及 MCP 参数不受影响。

这些参数管理进程资源，继续由部署侧提供，不写入业务数据库。绑定 HTTP 参数本身不启用模型或工具功能，不创建连接、不启动网络交互。只启用工具时也能独立装配，无需模型生成配置。

## 职责边界

- `HttpConnectionRuntime` 只接收通用资源参数及显式的连接、授权、凭据和出口端口。
- 模型出口继续读取平台数据库策略；HTTP／MCP 工具继续读取各自的出口白名单，三者不相互扩大授权范围。
- `NewAiGenerationProperties` 保留模型启用开关、环境凭据映射及 SSE 响应尾部排空期限。
- `ChatCompletionsSseOptions` 单独描述模型协议限制。正式装配保持单帧 64 KiB、请求体 1 MiB、默认排空期限 100 毫秒。
- `max-in-memory-bytes` 不替代单个 SSE 帧上限、连接的响应总字节上限或工具协议自身的大小限制；增大缓冲不会提高协议上限。
- 每个连接的连接超时、响应超时和响应总字节限制继续由 `ConnectionDefinition` 提供。

## 升级现有配置

将原 `arte.ai-new-generation.max-pools`、`connections-per-pool`、`pending-acquires`、`acquire-timeout` 分别移到
`arte.ai-new-http.model` 下的同名字段；原键已移除，直接绑定时会拒绝未知字段。

模型的 `arte.ai-new-generation.allowed-origins` 已移除，模型出口由数据库策略统一管理。工具的
`arte.ai-new-tool-http.allowed-origins` 和 `arte.ai-new-tool-mcp.allowed-origins` 继续保留。

`backend/profile/common.properties` 提供完整默认值，运行资源及生成示例已同步采用新前缀。SecretRef 环境变量映射、各功能开关、Worker
配置和连接业务定义不随资源参数一起搬迁。
