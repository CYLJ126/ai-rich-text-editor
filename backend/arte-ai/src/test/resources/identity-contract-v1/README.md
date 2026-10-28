# 第二批迁移前的身份与归属样本

2026-10-28 在移动 `ExecutionOwner` 及接入主体转换之前，用旧实现一次性生成并固定。 测试不能用当前实现重新生成这些预期文件。

- `invocation-user.json`、`invocation-service.json`：旧快照编码器生成的用户／服务调用，包含完整执行上下文和身份快照。
- `conversation.json`：包含原三元归属键的会话快照。
- `fixed-grant.json`：原固定 AI 授权配置，主体类型保持 `USER` 字符串编码。
- `vectors.json`：原主体／上下文／归属规范化 JSON、归属摘要、搜索归属键、工具调用幂等键及提交摘要。

使用样本时先规范化 JSON 排版，再比较内容及编码结果；空白格式不属于 v1 契约。
