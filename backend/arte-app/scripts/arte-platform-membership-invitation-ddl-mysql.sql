-- C11 邀请表，先执行 arte-platform-workspace-ddl-mysql.sql。
CREATE TABLE IF NOT EXISTS arte_platform_membership_invitation
(
    id                 varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '邀请记录唯一标识',
    tenant_id          varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属租户标识',
    workspace_id       varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '目标工作空间标识，NULL 表示仅邀请加入租户',
    target_subject_id  varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '受邀账号的稳定主体标识',
    issued_by          varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '发出邀请的稳定主体标识',
    role               int                                                    NOT NULL COMMENT '接受后授予的角色，2 为成员，3 为访客，租户邀请只允许成员',
    state              int                                                    NOT NULL COMMENT '邀请状态，0 为待接受，1 为已接受，2 为已撤销，3 为已过期',
    expires_at         datetime(6)                                            NOT NULL COMMENT '邀请到期时间，统一使用 UTC',
    accepted_member_id varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '接受邀请后关联的成员记录标识，按邀请范围指向租户或空间成员',
    version            bigint                                                 NOT NULL COMMENT '乐观锁版本号，邀请状态更新时递增',
    PRIMARY KEY (id),
    KEY ix_platform_membership_invitation_scope (tenant_id, workspace_id),
    KEY ix_platform_membership_invitation_target (target_subject_id, id),
    FOREIGN KEY (target_subject_id) REFERENCES arte_platform_principal_identity (subject_id),
    FOREIGN KEY (issued_by) REFERENCES arte_platform_principal_identity (subject_id),
    FOREIGN KEY (tenant_id) REFERENCES arte_platform_tenant (id),
    FOREIGN KEY (tenant_id, workspace_id) REFERENCES arte_platform_workspace (tenant_id, id),
    CHECK (role = 2 OR (workspace_id IS NOT NULL AND role = 3)),
    CHECK (state BETWEEN 0 AND 3 AND version >= 0),
    CHECK ((state = 1 AND accepted_member_id IS NOT NULL) OR (state <> 1 AND accepted_member_id IS NULL))
    ) ENGINE = InnoDB COMMENT ='租户与工作空间成员邀请';
