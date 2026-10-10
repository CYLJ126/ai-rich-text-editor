-- 第一批：只新增身份映射。部署前执行；应用不会自动执行 DDL 或回填。
-- 连接／会话时区统一 UTC；external_created_at 原样保留旧账户 create_time 以检测主键复用。
CREATE TABLE arte_platform_principal_identity
(
    subject_id          varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '稳定主体标识，不随账号改名变化，退休后不可重新绑定',
    provider            varchar(96) CHARACTER SET ascii COLLATE ascii_bin      NOT NULL COMMENT '身份来源标识，如 arte-rbac',
    external_user_id    varchar(64) CHARACTER SET ascii COLLATE ascii_bin      NOT NULL COMMENT '来源系统账号主键，当前为十进制字符串，与身份来源联合唯一',
    external_created_at datetime(6) NOT NULL COMMENT '来源账号原始创建时间，原样保留 create_time，用于检测账号主键复用',
    created_at          datetime(6) NOT NULL COMMENT '身份映射创建时间，统一使用 UTC',
    retired_at          datetime(6) NULL COMMENT '身份映射退休时间，统一使用 UTC；NULL 表示未退休，退休后保留关联并禁止重新绑定',
    version             bigint                                                 NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，初始为 0，退休更新时递增',
    PRIMARY KEY (subject_id),
    UNIQUE KEY uk_platform_external_identity (provider, external_user_id),
    CONSTRAINT ck_platform_identity_version CHECK (version >= 0),
    CONSTRAINT ck_platform_identity_retired CHECK (retired_at IS NULL OR retired_at >= created_at)
) ENGINE=InnoDB COMMENT='既有账户与稳定主体的不可变关联';
