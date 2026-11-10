-- 邀请与空间识别升级；MySQL 8+，须先安装成员邀请基础表。
-- 在启用新版写入前升级全部节点；新增 4=已拒绝，不删除邀请或成员历史。
-- 兼容旧版自动命名的 state CHECK 和新版显式命名的 CHECK，可重复执行。
SET
@arte_invitation_drop_checks = (
    SELECT GROUP_CONCAT(CONCAT('DROP CHECK `', REPLACE(tc.CONSTRAINT_NAME, '`', '``'), '`') SEPARATOR ', ')
    FROM information_schema.TABLE_CONSTRAINTS tc
    JOIN information_schema.CHECK_CONSTRAINTS cc
      ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
    WHERE tc.TABLE_SCHEMA = DATABASE()
      AND tc.TABLE_NAME = 'arte_platform_membership_invitation'
      AND tc.CONSTRAINT_TYPE = 'CHECK'
      AND REPLACE(LOWER(cc.CHECK_CLAUSE), '`', '') REGEXP 'state[[:space:]]+between[[:space:]]+0[[:space:]]+and[[:space:]]+[34]'
);
SET
@arte_invitation_upgrade = CONCAT('ALTER TABLE arte_platform_membership_invitation ',
    IF(@arte_invitation_drop_checks IS NULL, '', CONCAT(@arte_invitation_drop_checks, ', ')),
    'ADD CONSTRAINT ck_platform_invitation_state CHECK (state BETWEEN 0 AND 4 AND version >= 0)');
PREPARE arte_invitation_upgrade_stmt FROM @arte_invitation_upgrade;
EXECUTE arte_invitation_upgrade_stmt;
DEALLOCATE PREPARE arte_invitation_upgrade_stmt;
