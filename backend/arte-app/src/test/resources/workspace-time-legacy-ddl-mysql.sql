-- 迁移测试专用：冻结微秒迁移前的秒／纳秒结构。
-- Batch 2: MySQL 8+, InnoDB. Apply identity DDL first, then this file once through deployment migrations.
-- No automatic DDL or legacy resource backfill. All connections use UTC.
-- created_sec/created_nano preserve Instant precision and immutable keyset order (DATETIME(6) alone loses nanoseconds).
-- Policy rules, favorites, receipts and events use fixed versioned JSON structures, never executable scripts or Java type names.
-- IDs use case sensitive utf8mb4_bin; platform identifiers reject leading/trailing whitespace.
SET NAMES utf8mb4;
SET
time_zone = '+00:00';

CREATE TABLE arte_platform_tenant
(
    id                  varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '租户唯一 ID',
    name                varchar(100)                                           NOT NULL COMMENT '租户显示名称',
    type                int                                                    NOT NULL COMMENT '租户类型：0 个人，1 组织',
    personal_subject_id varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '个人租户关联的稳定主体 ID；组织租户为空，每个主体最多一个个人租户',
    state               int                                                    NOT NULL COMMENT '租户状态：0 正常，1 停用，2 关闭处理中',
    created_by          varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '创建人稳定主体 ID，仅用于审计，不代表资源归属或权限',
    version             bigint                                                 NOT NULL COMMENT '乐观锁版本号，非负；更新时用于版本比较',
    auth_epoch          bigint                                                 NOT NULL COMMENT '租户授权变更代次，非负；用于使旧授权缓存失效',
    created_sec         bigint                                                 NOT NULL COMMENT '创建时间的 Unix 秒数，与 created_nano 共同保存完整时间精度',
    created_nano        int                                                    NOT NULL COMMENT '创建时间的秒内纳秒数，范围 0 至 999999999',
    updated_sec         bigint                                                 NOT NULL COMMENT '最后更新时间的 Unix 秒数，与 updated_nano 配对',
    updated_nano        int                                                    NOT NULL COMMENT '最后更新时间的秒内纳秒数，范围 0 至 999999999',
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_personal_tenant(personal_subject_id),
    CHECK (version >= 0 AND auth_epoch >= 0),
    CHECK ((type = 0 AND personal_subject_id IS NOT NULL) OR (type = 1 AND personal_subject_id IS NULL)),
    FOREIGN KEY (personal_subject_id) REFERENCES arte_platform_principal_identity (subject_id)
) ENGINE=InnoDB COMMENT='平台租户：个人或组织的治理与资源隔离边界';

CREATE TABLE arte_platform_workspace
(
    tenant_id    varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属租户 ID',
    id           varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '工作空间唯一 ID',
    name         varchar(100)                                           NOT NULL COMMENT '工作空间显示名称',
    description  varchar(1000)                                          NOT NULL COMMENT '工作空间描述',
    state        int                                                    NOT NULL COMMENT '工作空间状态：0 正常，1 已归档，2 关闭处理中，3 已软删除',
    created_by   varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '创建人稳定主体 ID，仅用于审计，不代表资源归属或权限',
    version      bigint                                                 NOT NULL COMMENT '乐观锁版本号，非负；更新时用于版本比较',
    auth_epoch   bigint                                                 NOT NULL COMMENT '空间授权变更代次，非负；用于使旧授权缓存失效',
    created_sec  bigint                                                 NOT NULL COMMENT '创建时间的 Unix 秒数，与 created_nano 共同保存完整时间精度；作为不可变分页排序键',
    created_nano int                                                    NOT NULL COMMENT '创建时间的秒内纳秒数，范围 0 至 999999999；作为不可变分页排序键',
    updated_sec  bigint                                                 NOT NULL COMMENT '最后更新时间的 Unix 秒数，与 updated_nano 配对',
    updated_nano int                                                    NOT NULL COMMENT '最后更新时间的秒内纳秒数，范围 0 至 999999999',
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_workspace_scope(tenant_id,id),
    UNIQUE KEY uk_platform_workspace_scan_key(tenant_id,id,created_sec,created_nano),
    KEY          ix_platform_workspace_scan(created_sec DESC,created_nano DESC,tenant_id,id),
    CHECK (version >= 0 AND auth_epoch >= 0),
    CHECK (created_nano >= 0 AND created_nano < 1000000000 AND updated_nano >= 0 AND updated_nano < 1000000000),
    FOREIGN KEY (tenant_id) REFERENCES arte_platform_tenant (id)
) ENGINE=InnoDB COMMENT='平台工作空间：资源归属、协作和空间策略的边界';

CREATE TABLE arte_platform_tenant_member
(
    id         varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '租户成员记录唯一 ID',
    tenant_id  varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属租户 ID',
    subject_id varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '稳定主体 ID，关联平台身份，不使用可变登录名',
    role       int                                                    NOT NULL COMMENT '租户治理角色：0 负责人，1 管理员，2 成员，3 访客；不隐式继承到空间',
    state      int                                                    NOT NULL COMMENT '成员状态：0 有效，1 已撤销；撤销后保留成员记录',
    generation bigint                                                 NOT NULL COMMENT '成员资格代次，非负；重新加入时递增，防止旧授权恢复生效',
    version    bigint                                                 NOT NULL COMMENT '乐观锁版本号，非负；更新时用于版本比较',
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_tenant_member(tenant_id,subject_id),
    KEY        ix_platform_tenant_member_subject(subject_id,tenant_id),
    CHECK (version >= 0 AND generation >= 0),
    FOREIGN KEY (subject_id) REFERENCES arte_platform_principal_identity (subject_id),
    FOREIGN KEY (tenant_id) REFERENCES arte_platform_tenant (id)
) ENGINE=InnoDB COMMENT='平台租户成员：主体在租户内的治理角色和成员资格';

CREATE TABLE arte_platform_workspace_member
(
    id                     varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '空间成员记录唯一 ID',
    tenant_id              varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属租户 ID',
    workspace_id           varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属工作空间 ID，与租户 ID 共同确定空间范围',
    subject_id             varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '稳定主体 ID，关联平台身份，不使用可变登录名',
    subject_digest         char(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '主体 ID 的 SHA-256 十六进制摘要，仅用于缩短导航索引；鉴权仍核对原主体 ID',
    workspace_created_sec  bigint                                                 NOT NULL COMMENT '关联空间创建时间的 Unix 秒数，不可变；用于成员空间列表分页排序',
    workspace_created_nano int                                                    NOT NULL COMMENT '关联空间创建时间的秒内纳秒数，不可变；与 workspace_created_sec 配对',
    role                   int                                                    NOT NULL COMMENT '空间治理角色：0 负责人，1 管理员，2 成员，3 访客；不隐式授予私有正文读取权限',
    state                  int                                                    NOT NULL COMMENT '成员状态：0 有效，1 已撤销；撤销后保留成员记录',
    generation             bigint                                                 NOT NULL COMMENT '成员资格代次，非负；重新加入时递增，防止旧授权恢复生效',
    version                bigint                                                 NOT NULL COMMENT '乐观锁版本号，非负；更新时用于版本比较',
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_workspace_member(tenant_id,workspace_id,subject_id),
    KEY                    ix_platform_workspace_member_subject(subject_id,tenant_id,workspace_id),
    KEY                    ix_platform_workspace_member_scan(subject_digest,workspace_created_sec DESC,workspace_created_nano DESC,tenant_id,workspace_id),
    CHECK (version >= 0 AND generation >= 0),
    FOREIGN KEY (subject_id) REFERENCES arte_platform_principal_identity (subject_id),
    FOREIGN KEY (tenant_id) REFERENCES arte_platform_tenant (id),
    FOREIGN KEY (tenant_id, workspace_id) REFERENCES arte_platform_workspace (tenant_id, id),
    FOREIGN KEY (tenant_id, workspace_id, workspace_created_sec,
                 workspace_created_nano) REFERENCES arte_platform_workspace (tenant_id, id, created_sec, created_nano),
    FOREIGN KEY (tenant_id, subject_id) REFERENCES arte_platform_tenant_member (tenant_id, subject_id)
) ENGINE=InnoDB COMMENT='平台空间成员：主体在工作空间内的治理角色和成员资格';

CREATE TABLE arte_platform_tenant_policy
(
    personal_template_version    bigint NULL COMMENT '创建时个人模板版本；旧政策／组织为空，后续治理保留来源',
    tenant_id                    varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属租户 ID',
    rules                        longtext                                               NOT NULL COMMENT '租户策略规则 JSON，使用固定版本结构，不包含可执行脚本或 Java 类型名',
    members_may_create_workspace boolean                                                NOT NULL COMMENT '是否允许普通租户成员创建工作空间，仍须通过成员资格和基础授权检查',
    policy_schema_version        int                                                    NOT NULL COMMENT '策略 JSON 结构版本，正整数；不支持的版本拒绝处理',
    role_preset_version          int                                                    NOT NULL COMMENT '治理角色预设版本，正整数；不支持的版本拒绝处理',
    version                      bigint                                                 NOT NULL COMMENT '乐观锁版本号，非负；更新时用于版本比较',
    PRIMARY KEY (tenant_id),
    FOREIGN KEY (tenant_id) REFERENCES arte_platform_tenant (id),
    CHECK (policy_schema_version >= 1 AND role_preset_version >= 1 AND version >= 0),
    CHECK (personal_template_version IS NULL OR personal_template_version >= 1)
) ENGINE=InnoDB COMMENT='平台租户策略：租户内空间和资源行为的策略上限';

CREATE TABLE arte_platform_workspace_policy
(
    tenant_id             varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属租户 ID',
    workspace_id          varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属工作空间 ID，与租户 ID 共同确定空间范围',
    rules                 longtext                                               NOT NULL COMMENT '空间策略规则 JSON，使用固定版本结构；不得突破租户策略上限',
    policy_schema_version int                                                    NOT NULL COMMENT '策略 JSON 结构版本，正整数；不支持的版本拒绝处理',
    role_preset_version   int                                                    NOT NULL COMMENT '治理角色预设版本，正整数；不支持的版本拒绝处理',
    version               bigint                                                 NOT NULL COMMENT '乐观锁版本号，非负；更新时用于版本比较',
    PRIMARY KEY (tenant_id, workspace_id),
    FOREIGN KEY (tenant_id, workspace_id) REFERENCES arte_platform_workspace (tenant_id, id),
    CHECK (policy_schema_version >= 1 AND role_preset_version >= 1 AND version >= 0)
) ENGINE=InnoDB COMMENT='平台空间策略：受租户策略上限约束的空间资源行为规则';

CREATE TABLE arte_platform_workspace_preference
(
    subject_id           varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '稳定主体 ID，关联平台身份，不使用可变登录名',
    default_tenant_id    varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '系统初始化的默认个人空间所属租户 ID，用户偏好更新不可修改',
    default_workspace_id varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '系统初始化的默认个人空间 ID，用户偏好更新不可修改',
    startup_tenant_id    varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '用户指定启动空间的租户 ID；未指定时为空，与启动空间 ID 同时设置或清空',
    startup_workspace_id varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '用户指定启动空间 ID；仅为偏好，使用时重新校验访问资格',
    last_tenant_id       varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '最近使用空间的租户 ID；尚无记录时为空，与最近空间 ID 同时设置或清空',
    last_workspace_id    varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '最近使用空间 ID；仅为偏好，使用时重新校验访问资格',
    favorites            longtext                                               NOT NULL COMMENT '收藏空间集合 JSON，固定 scopes 结构，每项包含租户和空间 ID；不授予访问资格',
    version              bigint                                                 NOT NULL COMMENT '乐观锁版本号，非负；更新时用于版本比较',
    PRIMARY KEY (subject_id),
    UNIQUE KEY uk_platform_default_workspace(default_workspace_id),
    FOREIGN KEY (subject_id) REFERENCES arte_platform_principal_identity (subject_id),
    FOREIGN KEY (default_tenant_id, default_workspace_id) REFERENCES arte_platform_workspace (tenant_id, id),
    CHECK (version >= 0),
    CHECK ((startup_tenant_id IS NULL AND startup_workspace_id IS NULL) OR
           (startup_tenant_id IS NOT NULL AND startup_workspace_id IS NOT NULL)),
    CHECK ((last_tenant_id IS NULL AND last_workspace_id IS NULL) OR
           (last_tenant_id IS NOT NULL AND last_workspace_id IS NOT NULL))
) ENGINE=InnoDB COMMENT='平台用户空间偏好：默认、启动、最近使用及收藏空间';

CREATE TABLE arte_platform_command
(
    operation_id    varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '操作唯一 ID，用于关联命令回执、审计和事件',
    identity_digest char(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '操作者、租户、空间、命令类型及幂等键的 SHA-256 摘要；空范围参与编码',
    actor_id        varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '命令实际操作者的稳定主体 ID',
    tenant_id       varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '命令所属租户 ID；无租户范围的命令为空',
    workspace_id    varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '命令所属空间 ID；无空间范围的命令为空',
    command_type    varchar(96)                                            NOT NULL COMMENT '命令类型标识，与操作者、范围及幂等键共同确定命令身份',
    operation_key   varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '请求幂等键，按操作者、范围和命令类型隔离',
    request_digest  varchar(256)                                           NOT NULL COMMENT '规范化请求摘要，用于拒绝同一幂等键提交不同请求内容',
    result_schema   varchar(96)                                            NOT NULL COMMENT '结果结构标识，须为服务端支持的版本化结构，不是 Java 类名',
    receipt         longtext                                               NOT NULL COMMENT '命令回执 JSON，固定结构保存完整命令身份、请求摘要及结果等信息，不含凭据',
    version         bigint                                                 NOT NULL COMMENT '乐观锁版本号，非负；更新时用于版本比较',
    PRIMARY KEY (operation_id),
    UNIQUE KEY uk_platform_command_identity(identity_digest),
    CHECK (version >= 0)
) ENGINE=InnoDB COMMENT='平台命令回执：命令幂等、防重复执行及结果重放';

CREATE TABLE arte_platform_outbox
(
    id            varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '事件唯一 ID，用于发布和消费幂等去重',
    event_type    varchar(96)                                            NOT NULL COMMENT '公共事件类型标识',
    event         longtext                                               NOT NULL COMMENT '公共事件 JSON，使用固定版本结构，仅包含允许公开的元数据，不含正文或凭据',
    occurred_sec  bigint                                                 NOT NULL COMMENT '事件发生时间的 Unix 秒数，与 occurred_nano 配对',
    occurred_nano int                                                    NOT NULL COMMENT '事件发生时间的秒内纳秒数，范围 0 至 999999999',
    state         int                                                    NOT NULL COMMENT '发布状态：0 待发布，1 租约领取中，2 已发布到 Redis；已发布不代表已消费',
    attempts      bigint                                                 NOT NULL COMMENT '累计领取次数，初始为 0，每次领取递增',
    available_at  datetime(6) NULL COMMENT 'UTC 下次可领取时间：待发布时为重试时间，领取中为租约到期时间，已发布为空',
    lease_token   varchar(36) NULL COMMENT '当前领取租约令牌，确认或重试时比较，防止过期发布者修改记录',
    published_at  datetime(6) NULL COMMENT 'Redis 接收事件并确认后记录的 UTC 发布时间，尚未确认时为空',
    PRIMARY KEY (id),
    KEY           ix_platform_outbox_ready(available_at,id),
    CHECK (state >= 0 AND state <= 2 AND attempts >= 0)
) ENGINE=InnoDB COMMENT='平台事务事件发件箱：与业务事务同库提交，租约领取并发布到 Redis';

CREATE TABLE arte_platform_audit
(
    id               varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '审计记录唯一 ID',
    actor_subject_id varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '实际操作者的稳定主体 ID',
    tenant_id        varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '操作范围的租户 ID；无租户范围时为空',
    workspace_id     varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '操作范围的空间 ID；无空间范围时为空',
    action           varchar(96)                                            NOT NULL COMMENT '审计动作标识',
    operation_id     varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '关联操作 ID，对应命令回执及相关事件',
    reason           varchar(1000)                                          NOT NULL COMMENT '操作原因或审计说明',
    occurred_sec     bigint                                                 NOT NULL COMMENT '操作发生时间的 Unix 秒数，与 occurred_nano 配对',
    occurred_nano    int                                                    NOT NULL COMMENT '操作发生时间的秒内纳秒数，范围 0 至 999999999',
    PRIMARY KEY (id),
    KEY              ix_platform_audit_operation(operation_id),
    KEY              ix_platform_audit_scope(tenant_id,workspace_id)
) ENGINE=InnoDB COMMENT='平台操作审计：记录实际操作者、操作范围及关联命令';
