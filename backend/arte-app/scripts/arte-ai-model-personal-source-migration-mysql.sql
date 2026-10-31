-- 仅供已部署旧模型资源表的 MySQL 8 环境执行一次；新装完整基线已包含 source=2，不重复执行。
-- 停写并备份后执行；查明原 CHECK 名称，以单条 ALTER 原子替换约束，保留全部数据。
-- 原表只有一个涉及 source 且上限为 1 的 CHECK；无法唯一确认时故意报错，不猜测或跳过。
SELECT COUNT(*), MIN(tc.CONSTRAINT_NAME)
INTO @personal_source_count, @personal_source_check
FROM information_schema.TABLE_CONSTRAINTS tc
         JOIN information_schema.CHECK_CONSTRAINTS cc
              ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
WHERE tc.TABLE_SCHEMA = DATABASE()
  AND tc.TABLE_NAME = 'arte_ai_model_definition_source'
  AND tc.CONSTRAINT_TYPE = 'CHECK'
  AND cc.CHECK_CLAUSE LIKE '%source%'
  AND cc.CHECK_CLAUSE REGEXP '<=[[:space:]]*1([^0-9]|$)';
SET
@personal_source_ddl = IF(@personal_source_count=1,
    CONCAT('ALTER TABLE arte_ai_model_definition_source DROP CHECK `', REPLACE(@personal_source_check,'`','``'),
           '`, ADD CONSTRAINT ck_ai_model_definition_source CHECK (source >= 0 AND source <= 2)'),
    'SELECT * FROM arte_personal_source_migration_requires_exactly_one_old_check');
PREPARE personal_source_statement FROM @personal_source_ddl;
EXECUTE personal_source_statement;
DEALLOCATE PREPARE personal_source_statement;
