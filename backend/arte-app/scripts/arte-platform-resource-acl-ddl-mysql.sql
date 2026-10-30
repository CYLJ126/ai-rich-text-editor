-- Public resource ACL batch 1. Apply workspace DDL first. Run once through deployment migrations.
-- No startup DDL, backfill, model mapping or authorization source switch.
-- Digests bound physical indexes; original identities remain in checked columns/payloads.
SET NAMES utf8mb4;
SET
time_zone = '+00:00';
CREATE TABLE arte_platform_resource_access
(
    resource_key  char(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '租户 ID、资源类型及资源 ID 经长度前缀编码后的 SHA-256 摘要，用作物理主键；读取时仍核对原始资源身份',
    tenant_id     varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '资源所属租户 ID，与领域权威归属及载荷一致',
    resource_type varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin  NOT NULL COMMENT '受信资源类型标识，如 model；用于定位资源及匹配动作定义，不直接授予权限',
    resource_id   varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '领域资源的稳定 ID，与租户 ID、资源类型共同确定资源身份',
    workspace_id  varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '资源归属空间 ID；空间归属资源必填，租户级资源为空；须与领域权威归属一致',
    payload       longtext                                               NOT NULL COMMENT '资源 ACL 根记录的封闭 JSON：资源引用、归属、访问模式及 ACL 版本；不含正文、凭据或可执行策略',
    acl_version   bigint                                                 NOT NULL COMMENT 'ACL 乐观锁版本号，初始为 0；授权或访问模式变更时递增，通过 CAS 防止并发覆盖',
    PRIMARY KEY (resource_key),
    CHECK (acl_version >= 0),
    FOREIGN KEY (tenant_id) REFERENCES arte_platform_tenant (id),
    FOREIGN KEY (tenant_id, workspace_id) REFERENCES arte_platform_workspace (tenant_id, id)
) ENGINE=InnoDB COMMENT='公共资源 ACL 根：保存资源归属镜像、访问模式及授权版本，不直接表示某个主体具有权限';

CREATE TABLE arte_platform_resource_grant
(
    id                     varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '授权记录唯一 ID；一条记录表达一个目标的单个允许动作，撤销后保留历史',
    resource_key           char(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '被授权资源的身份摘要，关联 ACL 根；读取时核对载荷中的完整资源引用',
    source_identity_digest char(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '资源、动作、来源及来源操作 ID、目标范围及适用成员代次的 SHA-256 摘要；同来源授权防重，不覆盖其他独立来源',
    payload                longtext                                               NOT NULL COMMENT '授权记录的封闭 JSON：资源、目标、成员代次、动作、状态、期限、来源、来源操作 ID、签发主体、创建时间及版本；不使用 Java 类名多态',
    version                bigint                                                 NOT NULL COMMENT '授权记录乐观锁版本号，初始为 0；撤销时递增并通过 CAS 更新，不允许复活已撤销记录',
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_resource_grant_source(source_identity_digest),
    KEY                    ix_platform_resource_grants(resource_key,id),
    CHECK (version >= 0),
    FOREIGN KEY (resource_key) REFERENCES arte_platform_resource_access (resource_key)
) ENGINE=InnoDB COMMENT='公共资源允许授权：独立保存各来源的动作授权，实际访问仍校验成员资格、政策、期限及领域条件';
