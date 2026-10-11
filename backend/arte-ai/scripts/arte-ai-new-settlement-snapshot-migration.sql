-- 仅用于已部署且 arte_ai_settlement 仍使用 snapshot 列的数据库。
-- 先核对 information_schema.COLUMNS：snapshot 存在且 workspace_snapshot 不存在才执行。
-- 首次部署或已完成列名调整时跳过；MySQL 8+，仅重命名，不改 JSON 字节、类型、账本或幂等记录。
-- 与应用发布一起安排，旧进程仍使用 snapshot 时不能提前执行。应用启动不会自动执行本脚本。
ALTER TABLE arte_ai_settlement RENAME COLUMN snapshot TO workspace_snapshot;
