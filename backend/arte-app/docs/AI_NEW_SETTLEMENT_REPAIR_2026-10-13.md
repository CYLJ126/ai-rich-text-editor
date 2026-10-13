# 结算表修复验证（2026-10-13）

2026-10-13 21:31（Asia/Shanghai）完成运行环境的结算表列名对齐；21:33 完成数据库与 Worker 日志验证。

执行项目现有迁移：

```sql
ALTER TABLE arte_ai_settlement RENAME COLUMN snapshot TO workspace_snapshot;
```

迁移前确认旧列存在、新列不存在，其他结算字段齐全，运行中的应用 SQL 使用新列名。迁移前后的原有 11 条结算记录逐条核对主键、业务摘要和两份
JSON 的 SHA-256，全部保持一致。

Worker 按既有 Outbox 租约自动重试，成功恢复该调用的结算：

- 调用：a033876f-f773-457a-ac7c-6ea2b48fbbf1，执行状态仍为 SUCCEEDED。
- 21:32:26.412：insertSettlement 成功，影响 1 行，耗时 77 ms。
- 21:32:26.826：记录 `AI budget settlement committed`，状态 SETTLED，证据 PROVIDER_BILL。
- 数据库验证：该预留 RESERVED → SETTLED；对应账户 held=0，charged=0.000936；该预留结算事实只有 1 条，DISPATCH delivered=1。
- 所有 7 个 SUCCEEDED 调用的预算预留均为 SETTLED。
- 结算表总行数 11 → 12。
- 迁移后至本次检查没有新增 insertSettlement SQL 错误或 1054。历史错误保留在原日志中；最后一次旧列名错误发生于 21:30:
  25.647，早于迁移。

证据：[成功 SQL](/Users/cylj126/Workspace/my_project/arte_dev/backend/log/debug.log:9990)、[结算提交](/Users/cylj126/Workspace/my_project/arte_dev/backend/log/info.log:229)。

迁移脚本：[arte-ai-new-settlement-snapshot-migration.sql](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/scripts/arte-ai-new-settlement-snapshot-migration.sql:1)。
