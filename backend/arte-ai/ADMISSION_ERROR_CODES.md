# 新 AI 准入错误码

AdmissionException 继承 CommonException，错误身份仅由父类 resultCode 表达，兼容全局业务异常处理和 ResultContext.exception 转换。

- getResultCode()：统一的 ResultCodeEnum，用于内部枚举比较；标准响应的 code 字段使用其 getCode() 六位数字值。
- getMessage()：按读取线程当前语言解析安全文案，默认、简体中文、繁体中文和英文资源均已登记。

构造器只接受新 AI 的 ResultCodeEnum，调用方获得编译期检查，不再保存单独的字符串原因码：

```java
throw new AdmissionException(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT);
// getResultCode() = ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT
// getResultCode().getCode() = "205027"
```

内部判断直接比较 getResultCode() 与目标枚举。需要通用准入异常时显式使用 ResultCodeEnum.AI_ADMISSION_EXCEPTION，不再接收或解析任意字符串；数据库／编码等非预期故障保留原异常传播。

以下 205xxx 为可预期的准入／存储拒绝，305001 为未知准入错误兜底。新增枚举必须新增唯一数字值和全部语言文案；已发布数字值不得重排或复用。已有模块结果码保持原值。

| ResultCodeEnum 枚举 | 响应数字码 | 含义 |
|---|---|---|
| AI_CONFIGURATION_NOT_AVAILABLE | 205001 | 配置不存在或当前主体无权使用 |
| AI_CAPABILITY_DISABLED | 205002 | 能力或连接已停用 |
| AI_UNSUPPORTED_CAPABILITY | 205003 | 当前不支持该能力或输入格式 |
| AI_EXECUTION_LIMIT_EXCEEDED | 205004 | 请求超过执行限制 |
| AI_DEADLINE_EXCEEDED | 205005 | 执行期限已过 |
| AI_INPUT_LIMIT_EXCEEDED | 205006 | 输入超过大小限制 |
| AI_ONLY_SINGLE_USER_TEXT_SUPPORTED | 205007 | 当前仅支持单条用户文本 |
| AI_CONTEXT_SELECTION_NOT_SUPPORTED | 205008 | 当前不支持所选上下文配置 |
| AI_CONTEXT_CAPACITY_MISMATCH | 205009 | 上下文容量与绑定配置不一致 |
| AI_CONTEXT_CAPACITY_EXCEEDED | 205010 | 输入超过上下文容量 |
| AI_INVALID_CONTEXT_SNAPSHOT | 205011 | 上下文快照校验失败 |
| AI_CONTEXT_EXPIRED_OR_INVALID | 205012 | 上下文快照已过期或时间信息无效 |
| AI_CONTEXT_NOT_FOUND | 205013 | 上下文快照不存在或无权访问 |
| AI_OUTPUT_RESERVATION_EXCEEDED | 205014 | 输出 Token 上限超过上下文预留 |
| AI_CONVERSATION_CONFIGURATION_NOT_SUPPORTED | 205015 | 当前不支持所选会话配置 |
| AI_CONVERSATION_NOT_FOUND | 205016 | 会话不存在或无权访问 |
| AI_CONVERSATION_NOT_ACTIVE | 205017 | 会话当前不可提交调用 |
| AI_TURN_NOT_FOUND | 205018 | 轮次不存在或无权访问 |
| AI_HISTORY_NOT_SUPPORTED | 205019 | 当前不支持历史选择 |
| AI_CHAT_SELECTION_NOT_SUPPORTED | 205020 | 当前不支持所选聊天配置或分支 |
| AI_REGENERATION_NOT_SUPPORTED | 205021 | 当前不支持重新生成 |
| AI_INVALID_NEW_TURN | 205022 | 新轮次校验失败 |
| AI_BUDGET_NOT_AVAILABLE | 205023 | 预算不存在或当前主体无权使用 |
| AI_BUDGET_NOT_INITIALIZED | 205024 | 预算账户尚未初始化 |
| AI_BUDGET_CONFIGURATION_CONFLICT | 205025 | 预算账户与当前配置不一致 |
| AI_RATE_MISMATCH | 205026 | 费率版本不一致 |
| AI_IDEMPOTENCY_CONFLICT | 205027 | 幂等键已用于不同的请求内容 |
| AI_NOT_FOUND | 205028 | 目标执行记录不存在或无权访问 |
| AI_OWNER_MISMATCH | 205029 | 执行记录归属不匹配 |
| AI_VERSION_CONFLICT | 205030 | 执行记录版本已变化 |
| AI_LEASE_LOST | 205031 | 执行租约已失效 |
| AI_INVALID_STATE | 205032 | 当前执行状态不允许此操作 |
| AI_RECONCILIATION_REQUIRED | 205033 | 执行结果待核对，不能直接重试 |
| AI_CONVERSATION_BUSY | 205034 | 会话已有活跃调用 |
| AI_INSUFFICIENT_BUDGET | 205035 | 可用预算不足 |
| AI_CURRENCY_MISMATCH | 205036 | 预算币种不一致 |
| AI_CURSOR_EXPIRED | 205037 | 事件游标已过保留范围 |
| AI_DISPATCH_NOT_ENABLED | 205038 | 模型派发尚未启用 |
| AI_RECONCILIATION_NOT_ENABLED | 205039 | 执行核对尚未启用 |
| AI_CONTROL_NOT_ENABLED | 205040 | 执行控制尚未启用 |
| AI_RESULT_NOT_AVAILABLE | 205041 | 结果尚未生成或执行没有可读取结果 |
| AI_EVENT_WATCH_NOT_ENABLED | 205042 | 实时事件观看尚未启用 |
| AI_ADMISSION_EXCEPTION | 305001 | AI 请求受理异常 |

所有 StoreOutcome.Code 拒绝值均通过应用层 AdmissionException.fromStoreRejection(code) 显式映射为对应结果码。映射使用穷尽 switch，新增结果值须补充映射；APPLIED 和 REPLAYED 表示成功，尝试转换会抛出 IllegalArgumentException。存储契约仍使用自身的结果枚举。

数字结果码表达业务拒绝原因，不自动设置 HTTP 状态、指示安全重试或推进 Invocation 终态。数据库、编码和网络等非预期故障继续沿原 onError 传播；执行终态及重试仍依据权威记录和副作用事实。
