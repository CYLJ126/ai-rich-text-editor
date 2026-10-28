# 迁移前 v1 样本

2026-10-28 在移动公共类型之前，使用旧 `com.arte.ainew` 实现生成：

- `schema.json`：旧 `JacksonExecutionRecordCodec` 编码的 `ToolSchemaDefinition`，经旧 `CanonicalJson.normalize` 固定顺序。
- `tool-result.json`：旧编码器生成的 `InvocationResult.Tool`，包括来源、资源版本／草稿、产物和所有结构化值类别。
- `vectors.json`：旧规范化、SHA-256 和 UTF-8 长度前缀组合键输出，覆盖中文、emoji、空段及精确小数。

这些文件作为历史格式预期值固定保存；不能在测试运行时由当前实现重新生成。
