# 新 AI 聊天配置发现接口

当前阶段提供只读配置发现，配置来源仍为服务器的 `arte.ai-new` 固定发布配置。 不提供配置管理
CRUD、供应商模型列表探测或租户／空间列表发现；不需要新增表或执行 DDL。

## 请求与权限

```http
POST /arte/ai-new/configuration/discoverChatOptions
Authorization: Bearer <登录 Token>
Content-Type: application/json
Accept-Language: zh-CN
```

```json
{
  "scope": {
    "tenantId": "example-tenant",
    "workspaceId": "example-workspace"
  }
}
```

`/arte` 是现有应用上下文路径，控制器映射为 `/ai-new/configuration/discoverChatOptions`。 请求只选择
scope，必须由当前已登录身份的授权解析器验证。不能通过请求声明主体或权限。 需要 `ai:invoke`，不要求 `ai:read`、
`ai:conversation` 或 `ai:budget:admin`，也不要求
`Idempotency-Key`、会话或 budgetRef。MVC 请求线程捕获真实身份，异步执行不读取 ThreadLocal。 查询期限取服务器最大超时与 30
秒中的较小值。

## 响应契约

HTTP 200，沿用 `ResultContext` 包装，读取 `body.data.options`。以下为单个选项的数据示例， 数值对应 65536 Token 窗口、4096
最大输出和两分钟最大超时的服务器配置：

```json
{
  "options": [
    {
      "displayName": "deepseek-chat",
      "binding": {"type": "binding", "id": "default-text", "version": "v1"},
      "capability": {"type": "capability", "id": "text-generation", "version": "v1"},
      "contextWindowTokens": 65536,
      "limits": {
        "maxInputTokens": 65535,
        "maxOutputTokens": 4096,
        "maxInputBytes": 65536,
        "maxOutputBytes": 1048576,
        "maxTimeoutSeconds": 120
      },
      "defaults": {
        "maxInputTokens": 32768,
        "maxOutputTokens": 512,
        "timeoutSeconds": 60
      },
      "budgetRefs": ["example-budget"]
    }
  ]
}
```

| 字段                     | 含义                                                                                                      |
|--------------------------|-----------------------------------------------------------------------------------------------------------|
| displayName              | 当前采用 binding.remoteOperation 作为模型显示名称，不新增配置字段；同名时可同时展示 binding.id 和 version |
| binding / capability     | 精确固定版本引用，供现有 turnsForChat 请求原样使用；不返回 latest 或可执行配置内容                        |
| contextWindowTokens      | 当前绑定窗口，输入与输出的 Token 总额不得超过该值                                                         |
| limits.maxInputTokens    | 绑定窗口减一，为输出至少保留一个 Token；这是单字段上限，不表示与任意输出额度都能同时使用                  |
| limits.maxOutputTokens   | 服务器输出上限、GenerationOptions 契约上限 1000000、绑定窗口减一三者的最小值                              |
| limits.maxInputBytes     | UTF-8 输入／合并上下文的字节上限，与 Token 上限分别检查                                                   |
| limits.maxOutputBytes    | 执行输出字节上限，聊天入口由服务器构造，不要求客户端提交                                                  |
| limits.maxTimeoutSeconds | 服务器最大执行超时的整秒部分，与 turnsForChat.timeoutSeconds 相同单位                                     |
| defaults                 | 表单初始化推荐值，不替代生成参数默认值，也不保证预算足以支付                                              |
| budgetRefs               | 当前主体被授权、归属于当前主体、费率固定版本与此 binding 完全匹配的预算引用                               |

推荐输出为 `min(512, limits.maxOutputTokens)`；推荐输入为
`min(32768, contextWindowTokens - 推荐输出)`；推荐超时为
`min(60, limits.maxTimeoutSeconds)`。推荐额度不会改变已保存的前端配置，也不自动增大输出额度。 前端仍须满足
`maxInputTokens + maxOutputTokens <= contextWindowTokens`。 当前 Token 估算和完整历史加载规则继续沿用聊天接口，推荐额度不保证任意历史和文本都能装入。

结果以 binding 为单位，同一 capability 的不同 binding／不同固定版本分别保留。 按 binding.id、binding.version
的字符串顺序稳定排序，budgetRefs 同样排序。 配置规模由现有 NewAiProperties 限制，最多 256 个绑定，无分页、截断或隐式默认绑定。
整个响应白名单不包含 endpoint、connection、credential、SecretRef、环境变量名称／值、 Grant、owner、费率配置或其他用户的预算。

## 过滤与空结果

绑定进入选项列表前必须同时满足：

1. 当前授权允许该 binding ID；能力为可执行 GENERATION，连接启用。
2. 能力支持 TEXT_INPUT 和 STREAMING，窗口至少能分别容纳一个输入和输出 Token。
3. 实际注册的 ModelGateway 显式声明支持该绑定与连接的纯文本流式聊天。
4. 至少有一个当前主体授权、归属和费率版本均匹配的预算定义。

授权有效但没有符合条件的配置时，返回 HTTP 200 和 `data.options=[]`，不是 404。 未注册模型网关时同样返回空列表；仅开启受理层不会把未支持的模型展示为可选项。
默认 DefaultModelGateway 根据供应商 ID、协议固定版本、适配器登记的能力和文本／流式特性判断支持， 不访问供应商。自定义
ModelGateway 需覆盖 `supportsTextChat`；默认返回 false，不影响已有 generate 调用行为。

未初始化、余额为零或不足的预算仍会作为兼容引用返回，因为发现不读取账本。 选中预算后通过 [getBudget](BUDGET_HTTP_API.md)
查询账户状态和精确余额；金额保持十进制字符串。 预算未初始化及配置与账本冲突继续由 getBudget 报告。发现不初始化、修复或重置账户。

发现结果不保证 Worker 已启动、凭据有效、出口可达、供应商在线或预算足额。 执行提交和发送阶段继续重新授权并验证当前配置、参数及预算，不接受发现结果作为授权凭证。
缓存恢复后应重新发现并验证固定引用；不能自动替换尚未确认是否受理的幂等提交参数。

## 错误

| HTTP | 含义                                                     |
|------|----------------------------------------------------------|
| 400  | scope 缺失、空白、过长或 JSON／参数无效                  |
| 401  | 未登录                                                   |
| 403  | 缺少 ai:invoke、租户／空间不可用、授权已撤销或主体不一致 |
| 500  | 非预期服务错误，使用现有统一异常包装，不暴露底层配置文本 |

功能默认关闭；控制器跟随 `arte.ai-new.enabled` 注册。 查询只读取受信配置及授权信息，不调用 generate、不保存上下文、不创建
Invocation／Turn／Outbox、 不读取账本、不预留预算、不解析凭据、不初始化数据源或探测远端。 第 3 步的前端选择器尚未接入，本次现有聊天提交请求结构保持不变。

## 验证

在 backend 目录使用 JDK 21：

```sh
mvn -o -pl arte-ai -am \
  -Dtest=ConfigurationHttpIntegrationTest,ConfigurationAssemblyTest,GenerationAssemblyTest,GenerationGatewayTest,ChatHttpIntegrationTest,BudgetHttpIntegrationTest \
  -Dlog4j2.loggerContextFactory=org.apache.logging.log4j.core.impl.Log4jContextFactory \
  -Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

当前测试依赖同时包含 Log4j2 和 Logback 桥接组件，上面的 JVM 属性显式选择 Log4j2 上下文及 SLF4J
Provider，避免已有日志依赖冲突并兼容现有日志断言，不修改应用日志配置。 GenerationGatewayTest 和 ChatHttpIntegrationTest
使用本机临时端口模拟 HTTP／SSE，运行环境需允许监听 loopback；不访问真实供应商。

测试覆盖登录与 scope、权限隔离、精确版本、绑定去重边界、稳定顺序、费率版本兼容、空列表、
停用能力／连接、未支持的供应商／协议、推荐值容量约束、未初始化预算、公开字段白名单、 异步身份捕获、撤权后重新解析和默认关闭。真实
Spring 装配使用禁止访问的数据源及凭据解析器， 验证实际网关声明被发现服务使用而不打开数据库或发送请求。
