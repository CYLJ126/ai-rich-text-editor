-- Apply once after identity/workspace DDL. Existing installations also apply the provenance migration.
-- Revisions are append-only deployment artifacts. Runtime account only needs SELECT on these two tables.
CREATE TABLE arte_platform_personal_policy_template
(
    version                      bigint       NOT NULL COMMENT '模板修订版本，正整数；发布时递增，与租户政策版本相互独立',
    rules                        longtext     NOT NULL COMMENT '政策规则 JSON，使用固定版本结构；空白名单表示禁止，不包含可执行脚本或 Java 类型名',
    members_may_create_workspace boolean      NOT NULL COMMENT '是否允许新租户的普通成员创建工作空间，仍须通过成员资格和授权检查',
    policy_schema_version        int          NOT NULL COMMENT '政策规则 JSON 的结构版本，正整数；应用不支持的版本拒绝用于初始化',
    role_preset_version          int          NOT NULL COMMENT '治理角色预设版本，正整数；应用不支持的版本拒绝用于初始化',
    published_by                 varchar(256) NOT NULL COMMENT '发布该修订的部署操作者标识，仅用于记录发布来源，不授予租户或空间权限',
    published_at                 timestamp(6) NOT NULL COMMENT '该模板修订的发布时间，保存微秒精度',
    PRIMARY KEY (version),
    CHECK (version >= 1 AND policy_schema_version >= 1 AND role_preset_version >= 1)
) ENGINE=InnoDB COMMENT='新个人租户政策模板：不可变历史修订，只由部署发布';

CREATE TABLE arte_platform_personal_policy_template_head
(
    id      int    NOT NULL COMMENT '唯一发布指针的固定主键，值必须为 1',
    version bigint NOT NULL COMMENT '当前发布的模板修订版本，关联模板历史修订；仅供后续新建个人租户使用',
    PRIMARY KEY (id),
    CHECK (id = 1),
    FOREIGN KEY (version) REFERENCES arte_platform_personal_policy_template (version)
) ENGINE=InnoDB COMMENT='个人政策模板当前发布指针，发布与新租户初始化互斥';

-- Conservative v1; empty connection allowlists prohibit egress. MyEnum JSON uses numeric values.
INSERT INTO arte_platform_personal_policy_template
(version, rules, members_may_create_workspace, policy_schema_version, role_preset_version, published_by, published_at)
VALUES (1,
        '{"allowedCreationTypes":["article","collection","model","assistant"],"extraCreationRoles":{},"guestAiAllowed":false,"personalCredentialsAllowed":true,"allowedProtocols":[],"allowedProviders":[],"allowedDestinations":[],"exportTypes":["article","collection"],"egressTypes":["article","collection"],"shareTargetKinds":[0,1,2]}',
        false, 1, 1, 'deployment:initial', CURRENT_TIMESTAMP);
INSERT INTO arte_platform_personal_policy_template_head(id, version)
VALUES (1, 1);
