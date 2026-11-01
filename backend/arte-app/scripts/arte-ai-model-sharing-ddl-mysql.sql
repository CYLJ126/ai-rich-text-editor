-- 第四批：显式安装后启用 model-sharing；不回填旧模型，不转交个人密钥用途。
CREATE UNIQUE INDEX ux_ai_model_resource_tenant ON arte_ai_model_resource (resource_key, tenant_id);
CREATE TABLE arte_ai_model_sharing
(
    resource_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '稳定租户模型摘要',
    tenant_id    VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '模型所属租户',
    version      BIGINT                                                 NOT NULL COMMENT '范围及凭据用途 CAS 版本',
    payload      LONGTEXT                                               NOT NULL COMMENT '管理空间、使用空间、凭据代次和绑定成员代次的消费者；无密钥',
    PRIMARY KEY (resource_key),
    UNIQUE (resource_key, tenant_id),
    CHECK (version >= 0),
    FOREIGN KEY (resource_key, tenant_id) REFERENCES arte_ai_model_resource (resource_key, tenant_id),
    FOREIGN KEY (tenant_id) REFERENCES arte_platform_tenant (id)
) ENGINE=InnoDB COMMENT='租户模型独立使用范围与租户凭据用途';

CREATE TABLE arte_ai_model_use_scope
(
    resource_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '模型摘要',
    tenant_id    VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '与模型归属相同的租户',
    workspace_id VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '明确允许使用的空间',
    PRIMARY KEY (resource_key, workspace_id),
    KEY          ix_model_use_target(tenant_id,workspace_id,resource_key),
    FOREIGN KEY (resource_key, tenant_id) REFERENCES arte_ai_model_sharing (resource_key, tenant_id),
    FOREIGN KEY (tenant_id, workspace_id) REFERENCES arte_platform_workspace (tenant_id, id)
) ENGINE=InnoDB COMMENT='运行候选索引，仍核验 ACL 和凭据用途';
