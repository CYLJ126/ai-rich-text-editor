# S5-01：首个受控免费 HTTP 工具

> S5-03：新增默认关闭的受管 MCP 只读工具，沿用原管理／调用／Run
> 链路；当前协议、会话、结果子集与部署要求见 [MCP 说明](TOOL_MCP.md)。

> S5-02 增量：工具按次价格、可信费用证据、独立／Run 账户、费用核对及升级要求见 [工具计费说明](TOOL_BILLING.md)
> 。下文保留原批次边界，当前收费能力以该说明为准。

日期：2026-10-21。本批只交付 `arte.http.health@v1`：远端服务状态查询。沿用现有工具管理、发布、本人空间绑定、可靠受理、分类
Worker、结果查询和 ChatNew 工具集合；不新增同步执行接口。

## 1. 已实现的协议与产品范围

| 项目         | 首批约束                                                                                   |
|--------------|--------------------------------------------------------------------------------------------|
| 提供方／目标 | HTTP；`ExecutionTarget.Remote(connection, health)`，不伪装本地工具                         |
| 请求         | 固定完整 endpoint 上的 GET；arguments 必须为 `{}`，不发送聊天正文、历史或模型参数          |
| 认证         | NONE；无 Authorization、SecretRef、Cookie 或客户端自定义 Header；不意味着支持 Bearer       |
| 返回         | HTTP 200 且 application/json；仅投影 `status`，枚举 UP、DOWN、OUT_OF_SERVICE、UNKNOWN      |
| 敏感字段     | 丢弃原 JSON 的 details 等字段，不保存、显示或回传模型                                      |
| 计费         | 宿主完整契约白名单明确 FREE；不由 READ_ONLY、零价格或空 budgetRef 推断                     |
| 控制         | 无审批、最多一次 Attempt；不自动重试、不跟随重定向、不支持异步任务、远端取消或结果核对协议 |

返回 `status=UNKNOWN` 表示被查询服务报告的状态，仍是一次成功查询；它与 Invocation 的 UNKNOWN 执行事实不同。HTTP 503 即使带健康
JSON 也按远端 HTTP 失败处理，不能伪造成功。

POST、Bearer、参数／查询串映射、OpenAPI 导入、动态远端发现、付费工具和 MCP 均不属于本批。

## 2. 固定合同、权限和网络边界

`LocalToolRegistry` 保留原类名，但现在表示部署代码提供的静态定义来源：LOCAL 继续登记本地执行器，HTTP 显式登记
`HttpToolAdapter`。HTTP 实例必须精确匹配提供方、connection、operation 和工具版本；MCP 不接受登记。`refreshLocalCatalog`
仍刷新静态部署来源，绝不扫描用户 URL 或探测远端。

连接原文摘要写入工具默认配置及 const Schema，覆盖路径、协议、版本、期限和响应上限。发现、发布预检、发布事务、绑定解析、受理快照、派发和网关逐层核验；同版本换
endpoint 或连接合同会被不可变目录拒绝，不静默修改已发布工具。

宿主 `PlatformToolExecutionAccess` 继续复用当前身份、RBAC、空间、AI 资格和 `aiTool:invoke`；提交事务再检查完整免费合同。执行使用独立
`authorizeRemote`，旧的本地授权实现默认拒绝 HTTP。获取连接和真正发送前都再次调用当前平台资格检查，不复用受理时权限作为永久授权。

HTTP 使用独立的 `HttpConnectionRuntime` 实例和出口白名单，复用既有固定 DNS、地址校验、连接池、超时、单次发送和资源释放机制；模型仍使用原
`/chat/completions` 路径和原凭据要求。只允许白名单中的 HTTPS origin；显式 HTTP
回环地址仅供本机测试。当前沿用既有策略拒绝私网、链路本地、元数据地址及解析结果中的非公共 IP，未新增内网豁免。

池按连接合同／owner／本次校验后的 DNS 地址隔离；最多 8 池，每池 4 个连接、8 个等待；获取超时 2 秒，连接超时 3 秒，响应超时 5
秒。逻辑总期限仍覆盖受理、排队、授权、DNS 和 HTTP，最长 30 秒。响应流累计上限 64 KiB，JSON 深度／字符串／数字有界；最终结果信封上限
4 KiB。超限、非 JSON、非法状态值均失败。

## 3. 预算与失败语义

独立调用不创建 Account、Reservation、Settlement。工具聊天把该工具显式放入固定 FREE 映射，不继承模型账户、不创建工具
Allocation；模型仍按原 CHARGE 账本结算，工具描述和回传结果仍计入后续模型 Token 消耗。免费工具仍消耗 Run 工具次数、并发、时间与字节额度。

HTTP 状态失败使用 `TOOL_HTTP_STATUS_<status>`；响应类型／结构不合约使用 `TOOL_HTTP_RESPONSE_INVALID`，空响应使用
`TOOL_HTTP_RESPONSE_EMPTY`；其他读取／解析故障使用安全码 `TOOL_HTTP_RESPONSE_FAILED`。当前授权拒绝使用 `TOOL_ACCESS_DENIED`
，出口拒绝沿用既有 EGRESS 安全码，不回显原响应或异常正文。所有失败不自动重试。

受控 GET 的已观察超时／取消没有业务写入副作用：保存 NONE，Invocation 为 TIMED_OUT／CANCELLED，正常错误为
FAILED。不能把这条规则推广到业务写入、任意远端操作或远端取消成功。Worker 崩溃／失租等无法确认已保存结果的恢复仍按通用耐久规则保留
UNKNOWN；本批不提供远端结果核对或自动重发。

## 4. 配置与上线顺序

公共 profile 和运行资源已映射以下四项，默认关闭；endpoint 与 origins 默认为空，启用但缺配置会启动失败：

```properties
arte.ai-new-tool-http.enabled=false
arte.ai-new-tool-http.version=v1
arte.ai-new-tool-http.endpoint=
arte.ai-new-tool-http.allowed-origins=
```

在受信部署配置设置完整地址及独立白名单，例如 `
endpoint=https://status.example.com/actuator/health`、`allowed-origins=https://status.example.com`，然后显式开启 HTTP
开关。地址不可含用户信息、查询串或 fragment；白名单只填 origin。示例域名不是本批实际调用的服务。

沿用原 `ai-new`、TOOL、execution、分类调度开关；聊天另需 S4 工具模型、模型账户、空间批准、工具聊天和 Run
Worker。没有自动发布、建绑、授权角色或启动真实远端探测。

1. 停止新提交并按现有部署步骤排空旧工作项、领取和 permits／reservations；所有可能处理新 TOOL／Run 的节点升级解码器。
2. 为相同部署配置统一 HTTP 版本、endpoint、白名单与完整工具清单。清单可由登记来源的 `discover()` 元数据汇成
   `ToolCatalogStore.Batch` 后调用 `LocalToolDiscovery.manifest(batch)` 离线计算；该过程不访问网络。管理刷新返回当前节点
   checksum 供复核。
3. 按既有分类调度方案配置包含本地和 HTTP 工具的完整 `tool-manifest`，协调切换池与节点清单。不能只启用 HTTP
   开关并沿用旧本地清单；清单不一致时保持受理不可用。
4. 有权管理员刷新静态目录、核对默认配置中的连接摘要、以 `{}` → `{"status":"UP"}` 及实际固定
   GET／免费／只读依据审核发布；使用者准备本人空间绑定后按原预览／确认／提交路径调用，或在 ChatNew 选择该工具。
5. 调整 endpoint 或连接合同必须提升 HTTP version、重新发现审核发布并显式建绑／升级绑定，同时更新部署清单。当前节点只登记一个配置版本；切换前须排空旧调用，不能承诺旧待执行快照还能找到执行器。

新增 `tool-execution-snapshot` **v5** 区分远端工具，独立和 Run 子工具均使用 v5；旧本地独立 v1／v2／v3 和本地 Run 子工具 v4
原义不变，v5 改成 v4 会拒绝解码。没有新增表或 DDL，不能为本批重复初始化数据库。停用时先停止新受理、处理存量工作项再协调关闭／撤权，保留旧执行事实和账本。

## 5. 验证范围

新增 AI 测试覆盖真实回环 HTTP、静态发现／发布不外联、受理幂等、Worker／结果、聊天循环重启恢复、模型预算隔离、v5
防降级、不可变连接、未知参数、本地授权默认拒绝远端、发送前撤权、出口拒绝、302／401／403／429／500、不重试、JSON／类型／容量、超时与取消释放。

正式 arte-app 测试覆盖默认关闭、启用配置校验、真实宿主身份／RBAC／空间／AI 资格、公开 MVC
发布／建绑／预览／受理／结果、分类调度清单及撤权后重放拒绝。精确通过数量与命令见 [实施记录 §42](TOOL_IMPLEMENTATION.md#42-s5-01首个受控免费-http-工具)。

上述测试使用临时 H2 和模拟回环 HTTP，不读取业务库，不调用真实服务／模型。没有运行生产 MySQL、多节点故障切换、真实 HTTPS
提供者、性能压测或浏览器人工验收，不作为生产开放或性能 SLA 证明。
