-- 工作空间归档/保留型关闭。先安装工作空间基础表；不自动执行、不物理删除历史数据。
CREATE TABLE IF NOT EXISTS arte_platform_workspace_closure
(
    id
    varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '与受理命令回执一致的关闭操作 ID',
    tenant_id varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '固定租户',
    workspace_id varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '固定空间',
    state int NOT NULL COMMENT '0 待处理，1 处理中，2 等待证据，3 完成，4 失败',
    version bigint NOT NULL COMMENT '关闭操作 CAS 版本',
    updated_at datetime
(
    6
) NOT NULL COMMENT '最近推进时间，UTC',
    payload longtext NOT NULL COMMENT '封闭关闭操作 JSON，不含正文或凭据',
    PRIMARY KEY
(
    id
),
    UNIQUE KEY uk_platform_closure_scope
(
    tenant_id,
    workspace_id
),
    KEY ix_platform_closure_pending
(
    state,
    updated_at,
    id
),
    CONSTRAINT ck_platform_closure_state CHECK
(
    state
    BETWEEN
    0
    AND
    4
    AND
    version
    >=
    0
),
    FOREIGN KEY
(
    tenant_id,
    workspace_id
) REFERENCES arte_platform_workspace
(
    tenant_id,
    id
)
    ) ENGINE=InnoDB COMMENT='不可取消的工作空间关闭操作；软删除保留审计及账本';
CREATE TABLE IF NOT EXISTS arte_platform_workspace_closure_work
(
    operation_id
    varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '固定关闭操作',
    domain varchar
(
    96
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '稳定领域标识',
    work_key varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '固定工作项',
    version bigint NOT NULL COMMENT '工作项 CAS 版本',
    fencing bigint NOT NULL COMMENT '持久递增的租约令牌，释放租约不归零',
    lease_until datetime
(
    6
) NULL COMMENT '当前租约截止时间，UTC',
    payload longtext NOT NULL COMMENT '工作项及最小阻塞信息的封闭 JSON',
    PRIMARY KEY
(
    operation_id,
    domain,
    work_key
),
    CONSTRAINT ck_platform_closure_work_version CHECK
(
    version
    >=
    0
    AND
    fencing
    >=
    0
),
    FOREIGN KEY
(
    operation_id
) REFERENCES arte_platform_workspace_closure
(
    id
)
    ) ENGINE=InnoDB COMMENT='关闭固定领域工作项；未知与失败不代表完成';
-- 执行后检查：
-- SHOW CREATE TABLE arte_platform_workspace_closure;
-- SHOW CREATE TABLE arte_platform_workspace_closure_work;
