-- Apply once after public workspace and new AI execution DDL. No startup DDL or implicit config import.
CREATE TABLE arte_ai_control_revision
(
    version      bigint       NOT NULL COMMENT '已发布控制面修订版本，正整数；历史不可覆盖',
    payload      longtext     NOT NULL COMMENT '模型、连接、费率、默认模板和旧范围授权的固定结构 JSON；仅保存密钥引用',
    published_by varchar(256) NOT NULL COMMENT '发布者稳定主体 ID，供审计关联',
    published_at timestamp(6) NOT NULL COMMENT '修订发布时间，微秒精度',
    PRIMARY KEY (version),
    CHECK (version >= 1)
) ENGINE=InnoDB COMMENT='AI 数据库控制面的不可变发布历史';

CREATE TABLE arte_ai_control_head
(
    id      int    NOT NULL COMMENT '唯一发布指针，固定为 1',
    version bigint NOT NULL COMMENT '当前发布版本；0 表示尚未显式导入配置',
    PRIMARY KEY (id),
    CHECK (id = 1 AND version >= 0)
) ENGINE=InnoDB COMMENT='AI 控制面当前发布指针';
INSERT INTO arte_ai_control_head(id, version)
VALUES (1, 0);

CREATE TABLE arte_ai_workspace_configuration
(
    tenant_id    varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公共租户 ID',
    workspace_id varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公共空间 ID，与租户共同限定范围',
    payload      longtext                                               NOT NULL COMMENT '空间模型固定引用、独立预算预设、启用状态及模板来源；不包含历史消息或密钥',
    version      bigint                                                 NOT NULL COMMENT '空间 AI 配置的乐观锁版本，更新时递增',
    PRIMARY KEY (tenant_id, workspace_id),
    FOREIGN KEY (tenant_id, workspace_id) REFERENCES arte_platform_workspace (tenant_id, id),
    CHECK (version >= 0)
) ENGINE=InnoDB COMMENT='空间独立 AI 设置；模板发布不覆盖已有配置';

CREATE TABLE arte_ai_budget_definition
(
    budget_ref   varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '预算唯一引用，按空间、主体及费率确定，不复用旧账户',
    tenant_id    varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '预算所属租户 ID',
    workspace_id varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '预算所属空间 ID',
    subject_id   varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '账户所属稳定主体 ID；不同主体保持独立账本',
    payload      longtext                                               NOT NULL COMMENT '预算定义 JSON；余额、预留和已计费用继续由 arte_ai_account 权威保存',
    version      bigint                                                 NOT NULL COMMENT '预算定义乐观锁版本，非负',
    PRIMARY KEY (budget_ref),
    KEY          ix_ai_budget_scope(tenant_id,workspace_id,subject_id),
    FOREIGN KEY (tenant_id, workspace_id) REFERENCES arte_platform_workspace (tenant_id, id),
    FOREIGN KEY (subject_id) REFERENCES arte_platform_principal_identity (subject_id),
    CHECK (version >= 0)
) ENGINE=InnoDB COMMENT='公共空间预算定义，不覆盖既有 AI 历史和账本归属';
