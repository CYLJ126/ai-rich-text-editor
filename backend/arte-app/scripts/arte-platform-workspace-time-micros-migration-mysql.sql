-- 开发库微秒时间迁移，MySQL 8+；先停止该库全部旧版写入并备份相关表。
-- 同一 mysql 会话中：SET @arte_workspace_time_migration_confirmed=1; SOURCE 本文件;
-- 不使用存储过程，无需 CREATE/ALTER ROUTINE 权限。须遇错停止（不要使用 mysql --force）。
-- DDL 隐式提交；旧列保留至新约束安装完成，失败可修复后重跑。失败后关闭会话以释放迁移锁。
-- 不删除业务记录，不改写历史回执、事件或审计；旧 v2 游标由新应用 v3 前缀失效。
-- ARTE_TIME_STOP_* 表名是故意设置的错误诊断：检查未通过时 PREPARE 即失败，不执行后续变更。
SET
time_zone = '+00:00';
DELIMITER
$$
SET SESSION group_concat_max_len=65535$$
SET @arte_time_sql = IF(NOT (DATABASE() IS NOT NULL AND COALESCE(@arte_workspace_time_migration_confirmed,0)=1), 'SELECT * FROM `ARTE_TIME_STOP_CONFIRM_STOPPED_WRITERS_AND_BACKUP`', 'DO 0')$$
PREPARE arte_time_statement FROM @arte_time_sql$$
EXECUTE arte_time_statement$$
DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT (@@foreign_key_checks=1), 'SELECT * FROM `ARTE_TIME_STOP_FOREIGN_KEY_CHECKS_REQUIRED`', 'DO 0')$$
PREPARE arte_time_statement FROM @arte_time_sql$$
EXECUTE arte_time_statement$$
DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_lock=CONCAT('arte.workspace.time.',LEFT(SHA2(DATABASE(),256),40))$$
SELECT GET_LOCK(@arte_time_lock, 0)
INTO @arte_time_lock_acquired$$ SET @arte_time_sql = IF(NOT (COALESCE(@arte_time_lock_acquired,0)=1), 'SELECT * FROM `ARTE_TIME_STOP_MIGRATION_ALREADY_RUNNING`', 'DO 0')$$
PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @old_tenant = EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name ='arte_platform_tenant' AND column_name ='created_sec')$$
SET @old_workspace = EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name ='arte_platform_workspace' AND column_name ='created_sec')$$
SET @old_workspace_member = EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name ='arte_platform_workspace_member' AND column_name ='workspace_created_sec')$$

-- 所有旧值在新增列之前检查；使用范围覆盖 1000～9999 年，避免 FROM_UNIXTIME 的范围与时区依赖。
SET @arte_time_bad=0$$
SET @arte_time_sql = IF(@old_tenant=1, 'SELECT EXISTS (SELECT 1 FROM arte_platform_tenant WHERE
    created_nano<0 OR created_nano>=1000000000 OR updated_nano<0 OR updated_nano>=1000000000
    OR created_sec < -30610224000 OR created_sec > 253402300799
    OR updated_sec < -30610224000 OR updated_sec > 253402300799
    OR updated_sec<created_sec OR (updated_sec=created_sec AND updated_nano<created_nano)) INTO @arte_time_bad', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT (@arte_time_bad=0), 'SELECT * FROM `ARTE_TIME_STOP_TENANT_INVALID_LEGACY_TIME`', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_bad=0$$
SET @arte_time_sql = IF(@old_workspace=1, 'SELECT EXISTS (SELECT 1 FROM arte_platform_workspace WHERE
    created_nano<0 OR created_nano>=1000000000 OR updated_nano<0 OR updated_nano>=1000000000
    OR created_sec < -30610224000 OR created_sec > 253402300799
    OR updated_sec < -30610224000 OR updated_sec > 253402300799
    OR updated_sec<created_sec OR (updated_sec=created_sec AND updated_nano<created_nano)) INTO @arte_time_bad', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT (@arte_time_bad=0), 'SELECT * FROM `ARTE_TIME_STOP_WORKSPACE_INVALID_LEGACY_TIME`', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_bad=0$$
SET @arte_time_sql = IF(@old_workspace_member=1, 'SELECT EXISTS (SELECT 1 FROM arte_platform_workspace_member WHERE
    workspace_created_nano<0 OR workspace_created_nano>=1000000000
    OR workspace_created_sec < -30610224000 OR workspace_created_sec > 253402300799) INTO @arte_time_bad', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT (@arte_time_bad=0), 'SELECT * FROM `ARTE_TIME_STOP_MEMBER_INVALID_LEGACY_TIME`', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_bad=0$$
SET @arte_time_sql = IF(@old_workspace_member=1 AND @old_workspace=1, 'SELECT EXISTS (SELECT 1 FROM arte_platform_workspace_member m LEFT JOIN arte_platform_workspace w
    ON w.tenant_id=m.tenant_id AND w.id=m.workspace_id
    WHERE w.id IS NULL OR m.workspace_created_sec<>w.created_sec OR m.workspace_created_nano<>w.created_nano) INTO @arte_time_bad', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT (@arte_time_bad=0), 'SELECT * FROM `ARTE_TIME_STOP_MEMBER_KEY_MISMATCH`', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$

-- 增加新列，已有列必须为 DATETIME(6)，支持阶段性失败后的再次运行。
SET @arte_time_sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name ='arte_platform_tenant' AND column_name ='created_at'), 'ALTER TABLE arte_platform_tenant ADD COLUMN created_at DATETIME(6) NULL COMMENT ''UTC 微秒时间''', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT (EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name ='arte_platform_tenant' AND column_name ='created_at' AND data_type='datetime' AND datetime_precision=6)), 'SELECT * FROM `ARTE_TIME_STOP_TENANT_CREATED_AT_UNEXPECTED_TYPE`', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name ='arte_platform_tenant' AND column_name ='updated_at'), 'ALTER TABLE arte_platform_tenant ADD COLUMN updated_at DATETIME(6) NULL COMMENT ''UTC 微秒时间''', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT (EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name ='arte_platform_tenant' AND column_name ='updated_at' AND data_type='datetime' AND datetime_precision=6)), 'SELECT * FROM `ARTE_TIME_STOP_TENANT_UPDATED_AT_UNEXPECTED_TYPE`', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name ='arte_platform_workspace' AND column_name ='created_at'), 'ALTER TABLE arte_platform_workspace ADD COLUMN created_at DATETIME(6) NULL COMMENT ''UTC 微秒时间''', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT (EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name ='arte_platform_workspace' AND column_name ='created_at' AND data_type='datetime' AND datetime_precision=6)), 'SELECT * FROM `ARTE_TIME_STOP_WORKSPACE_CREATED_AT_UNEXPECTED_TYPE`', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name ='arte_platform_workspace' AND column_name ='updated_at'), 'ALTER TABLE arte_platform_workspace ADD COLUMN updated_at DATETIME(6) NULL COMMENT ''UTC 微秒时间''', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT (EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name ='arte_platform_workspace' AND column_name ='updated_at' AND data_type='datetime' AND datetime_precision=6)), 'SELECT * FROM `ARTE_TIME_STOP_WORKSPACE_UPDATED_AT_UNEXPECTED_TYPE`', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name ='arte_platform_workspace_member' AND column_name ='workspace_created_at'), 'ALTER TABLE arte_platform_workspace_member ADD COLUMN workspace_created_at DATETIME(6) NULL COMMENT ''UTC 微秒时间''', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT (EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name ='arte_platform_workspace_member' AND column_name ='workspace_created_at' AND data_type='datetime' AND datetime_precision=6)), 'SELECT * FROM `ARTE_TIME_STOP_WORKSPACE_MEMBER_WORKSPACE_CREATED_AT_UNEXPECTED_TYPE`', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$

-- 显式纳秒 DIV 1000 截断，不依赖数据库或驱动舍入。成员独立转换后再核对父键，不静默修复损坏副本。
SET @arte_time_sql = IF(@old_tenant=1, 'UPDATE arte_platform_tenant SET
    created_at=TIMESTAMPADD(MICROSECOND,created_nano DIV 1000,TIMESTAMPADD(SECOND,created_sec,CAST(''1970-01-01 00:00:00'' AS DATETIME))),
    updated_at=TIMESTAMPADD(MICROSECOND,updated_nano DIV 1000,TIMESTAMPADD(SECOND,updated_sec,CAST(''1970-01-01 00:00:00'' AS DATETIME)))', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT (NOT EXISTS (SELECT 1 FROM arte_platform_tenant WHERE created_at IS NULL OR updated_at IS NULL OR updated_at<created_at)), 'SELECT * FROM `ARTE_TIME_STOP_TENANT_CONVERTED_TIME_INVALID`', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(@old_workspace=1, 'UPDATE arte_platform_workspace SET
    created_at=TIMESTAMPADD(MICROSECOND,created_nano DIV 1000,TIMESTAMPADD(SECOND,created_sec,CAST(''1970-01-01 00:00:00'' AS DATETIME))),
    updated_at=TIMESTAMPADD(MICROSECOND,updated_nano DIV 1000,TIMESTAMPADD(SECOND,updated_sec,CAST(''1970-01-01 00:00:00'' AS DATETIME)))', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT (NOT EXISTS (SELECT 1 FROM arte_platform_workspace WHERE created_at IS NULL OR updated_at IS NULL OR updated_at<created_at)), 'SELECT * FROM `ARTE_TIME_STOP_WORKSPACE_CONVERTED_TIME_INVALID`', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(@old_workspace_member=1, 'UPDATE arte_platform_workspace_member SET workspace_created_at=TIMESTAMPADD(MICROSECOND,workspace_created_nano DIV 1000,TIMESTAMPADD(SECOND,workspace_created_sec,CAST(''1970-01-01 00:00:00'' AS DATETIME)))', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT (NOT EXISTS (SELECT 1 FROM arte_platform_workspace_member m LEFT JOIN arte_platform_workspace w
    ON w.tenant_id=m.tenant_id AND w.id=m.workspace_id
    WHERE m.workspace_created_at IS NULL OR w.id IS NULL OR m.workspace_created_at<>w.created_at)), 'SELECT * FROM `ARTE_TIME_STOP_CONVERTED_MEMBER_KEY_MISMATCH`', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
ALTER TABLE arte_platform_tenant MODIFY created_at DATETIME(6) NOT NULL COMMENT '不可变 UTC 微秒创建时间', MODIFY updated_at DATETIME(6) NOT NULL COMMENT 'UTC 非递减微秒更新时间' $$
ALTER TABLE arte_platform_workspace MODIFY created_at DATETIME(6) NOT NULL COMMENT '不可变 UTC 微秒创建时间', MODIFY updated_at DATETIME(6) NOT NULL COMMENT 'UTC 非递减微秒更新时间' $$
ALTER TABLE arte_platform_workspace_member MODIFY workspace_created_at DATETIME(6) NOT NULL COMMENT '空间的不可变 UTC 微秒创建键' $$

-- 先安装新联合外键及非递减约束，再拆除旧外键和列，始终保持 foreign_key_checks=1。
    SET @arte_time_sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name ='arte_platform_workspace' AND index_name='uk_platform_workspace_created_at'), 'ALTER TABLE arte_platform_workspace ADD UNIQUE KEY uk_platform_workspace_created_at(tenant_id,id,created_at)', 'DO 0')$$
    PREPARE arte_time_statement FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
    SET @arte_time_sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema =DATABASE() AND table_name ='arte_platform_workspace_member' AND constraint_name ='fk_platform_workspace_member_created_at'), 'ALTER TABLE arte_platform_workspace_member ADD CONSTRAINT fk_platform_workspace_member_created_at FOREIGN KEY(tenant_id,workspace_id,workspace_created_at) REFERENCES arte_platform_workspace(tenant_id,id,created_at)', 'DO 0')$$
    PREPARE arte_time_statement FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
    SET @arte_time_sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema =DATABASE() AND table_name ='arte_platform_tenant' AND constraint_name ='ck_platform_tenant_time'), 'ALTER TABLE arte_platform_tenant ADD CONSTRAINT ck_platform_tenant_time CHECK(updated_at>=created_at)', 'DO 0')$$
    PREPARE arte_time_statement FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
    SET @arte_time_sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema =DATABASE() AND table_name ='arte_platform_workspace' AND constraint_name ='ck_platform_workspace_time'), 'ALTER TABLE arte_platform_workspace ADD CONSTRAINT ck_platform_workspace_time CHECK(updated_at>=created_at)', 'DO 0')$$
    PREPARE arte_time_statement FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SELECT GROUP_CONCAT(DISTINCT CONCAT('DROP FOREIGN KEY `',REPLACE(constraint_name,'`','``'),'`') SEPARATOR ',')
INTO @arte_time_drops
FROM information_schema.key_column_usage
WHERE constraint_schema = DATABASE()
  AND table_name = 'arte_platform_workspace_member'
  AND column_name IN ('workspace_created_sec', 'workspace_created_nano')
  AND referenced_table_name IS NOT NULL$$ SET @arte_time_sql=IF(@arte_time_drops IS NULL,'DO 0',CONCAT('ALTER TABLE arte_platform_workspace_member ',@arte_time_drops))$$
PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SELECT GROUP_CONCAT(DISTINCT CONCAT('DROP CHECK `',REPLACE(tc.constraint_name,'`','``'),'`') SEPARATOR ',')
INTO @arte_time_drops
FROM information_schema.table_constraints tc
         JOIN information_schema.check_constraints cc
              ON tc.constraint_schema = cc.constraint_schema AND tc.constraint_name = cc.constraint_name
WHERE tc.constraint_schema = DATABASE()
  AND tc.table_name = 'arte_platform_workspace'
  AND (cc.check_clause LIKE '%created_nano%' OR cc.check_clause LIKE '%updated_nano%') $$
SET @arte_time_sql=IF(@arte_time_drops IS NULL, 'DO 0', CONCAT('ALTER TABLE arte_platform_workspace ', @arte_time_drops))$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name ='arte_platform_workspace' AND index_name='ix_platform_workspace_scan' AND column_name ='created_sec'), 'ALTER TABLE arte_platform_workspace DROP INDEX ix_platform_workspace_scan', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name ='arte_platform_workspace' AND index_name='ix_platform_workspace_scan'), 'ALTER TABLE arte_platform_workspace ADD KEY ix_platform_workspace_scan(created_at DESC,tenant_id,id)', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name ='arte_platform_workspace_member' AND index_name='ix_platform_workspace_member_scan' AND column_name ='workspace_created_sec'), 'ALTER TABLE arte_platform_workspace_member DROP INDEX ix_platform_workspace_member_scan', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name ='arte_platform_workspace_member' AND index_name='ix_platform_workspace_member_scan'), 'ALTER TABLE arte_platform_workspace_member ADD KEY ix_platform_workspace_member_scan(subject_digest,workspace_created_at DESC,tenant_id,workspace_id)', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name ='arte_platform_workspace' AND index_name='uk_platform_workspace_scan_key'), 'ALTER TABLE arte_platform_workspace DROP INDEX uk_platform_workspace_scan_key', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(@old_workspace_member=1, 'ALTER TABLE arte_platform_workspace_member DROP COLUMN workspace_created_sec,DROP COLUMN workspace_created_nano', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(@old_workspace=1, 'ALTER TABLE arte_platform_workspace DROP COLUMN created_sec,DROP COLUMN created_nano,DROP COLUMN updated_sec,DROP COLUMN updated_nano', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
SET @arte_time_sql = IF(@old_tenant=1, 'ALTER TABLE arte_platform_tenant DROP COLUMN created_sec,DROP COLUMN created_nano,DROP COLUMN updated_sec,DROP COLUMN updated_nano', 'DO 0')$$
    PREPARE arte_time_statement
FROM @arte_time_sql$$
    EXECUTE arte_time_statement$$
    DEALLOCATE PREPARE arte_time_statement$$
    DO RELEASE_LOCK(@arte_time_lock)$$
SELECT 'UTC_DATETIME6_V3'                                    AS workspace_time_contract,
       (SELECT COUNT(*) FROM arte_platform_tenant)           AS tenants,
       (SELECT COUNT(*) FROM arte_platform_workspace)        AS workspaces,
       (SELECT COUNT(*) FROM arte_platform_workspace_member) AS workspace_members$$
    DELIMITER;
