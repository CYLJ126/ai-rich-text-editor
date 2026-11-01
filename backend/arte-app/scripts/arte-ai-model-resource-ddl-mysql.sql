-- Batch 3.2: public workspace and ACL DDL first; no automatic migration or ownership inference.
-- Sources and exact references remain immutable. No execution/account/credential data is moved.
SET NAMES utf8mb4;
SET
time_zone = '+00:00';
CREATE TABLE arte_ai_model_resource
(
    resource_key char(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '租户 ID、model 资源类型及资源 ID 经长度前缀编码后的 SHA-256 摘要，用作物理主键；读取时仍核对完整资源身份',
    tenant_id    varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '模型管理资源所属租户 ID，与资源引用、主空间及载荷中的归属一致',
    resource_id  varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '服务器生成的稳定模型管理资源 ID；独立于 bindingRef，新发布版本沿用原资源身份及 ACL',
    workspace_id varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '模型固定管理空间；空间模型亦为归属空间，租户模型真实归属取载荷 ownership，使用范围另行授权',
    payload      longtext                                               NOT NULL COMMENT '模型管理元数据的固定结构 JSON：资源引用、归属、名称、创建者、状态、绑定族、版本及创建时间；不含凭据明文或预算账本',
    version      bigint                                                 NOT NULL COMMENT '管理资源乐观锁版本，初始为 0；关联新发布版本或停用时递增，通过 CAS 更新，与公共 ACL 版本独立',
    PRIMARY KEY (resource_key),
    CHECK (version >= 0),
    FOREIGN KEY (tenant_id, workspace_id) REFERENCES arte_platform_workspace (tenant_id, id)
) ENGINE=InnoDB COMMENT='稳定模型管理资源：保存空间归属及当前管理状态，创建者、凭据责任与执行归属分别处理';

CREATE TABLE arte_ai_model_version
(
    binding_key     char(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '租户 ID 与精确 bindingRef 经长度前缀编码后的 SHA-256 摘要；保证同租户同一发布引用仅映射到一个管理资源',
    resource_key    char(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '关联的稳定模型管理资源身份摘要；同一资源可关联绑定族的多个精确发布版本',
    tenant_id       varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '映射所属租户 ID；不同租户可经各自明确授权关联同一固定发布引用',
    binding_id      varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '精确发布引用中的 binding 标识，关联后须属于管理资源已确定的绑定族，不作为管理资源 ID',
    binding_version varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'binding 的不可变字符串版本，与 binding_id 共同定位精确发布内容；不解析 latest 或覆盖旧版本',
    snapshot_digest char(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '资源身份、发布内容摘要、来源、目录修订、releaseRef、关联主体及时间的 SHA-256 摘要；读取时核验历史快照完整性',
    payload         longtext                                               NOT NULL COMMENT '不可变版本关联的固定结构 JSON：资源引用、绑定及连接和费率快照、来源、明确目录修订、发布标识、关联主体及时间；仅含凭据引用',
    PRIMARY KEY (binding_key),
    KEY             ix_ai_model_versions(resource_key,binding_key),
    FOREIGN KEY (resource_key) REFERENCES arte_ai_model_resource (resource_key),
    FOREIGN KEY (tenant_id) REFERENCES arte_platform_tenant (id)
) ENGINE=InnoDB COMMENT='模型精确发布版本映射：保留不可变关联及快照，新版本共享稳定管理资源和公共 ACL';

CREATE TABLE arte_ai_model_definition_source
(
    definition_key     char(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '定义类型、ID 及精确版本经长度前缀编码后的 SHA-256 摘要；全局唯一，读取冲突行时核对原始元组',
    definition_type    varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin  NOT NULL COMMENT '已登记定义类型；首批为模型 binding 及其 capability、connection、rate 依赖',
    definition_id      varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '发布定义的原始 ID，与类型及版本共同确定内容身份，不代表资源归属或使用授权',
    definition_version varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '发布定义的精确字符串版本；同一精确引用不得被不同内容覆盖',
    source int NOT NULL COMMENT '已登记的内容权威来源：0 FIXED 固定部署定义，1 DATABASE 数据库已发布定义，2 PERSONAL 个人发布；相同依赖保留原权威',
    content_digest     char(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '该定义规范化内容的 SHA-256 摘要；能力特性集合排序、数值归一化，用于拒绝同一精确引用的内容冲突',
    PRIMARY KEY (definition_key),
    CONSTRAINT ck_ai_model_definition_source CHECK (source >= 0 AND source <= 2)
) ENGINE=InnoDB COMMENT='模型发布定义来源登记：固定精确引用的内容权威及摘要，不直接授予模型、凭据或预算权限';
