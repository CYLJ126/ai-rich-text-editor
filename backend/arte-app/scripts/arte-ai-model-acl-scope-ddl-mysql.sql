-- Batch 3.3. Apply public workspace, public ACL and model resource migrations before this script.
-- Deploy on every API/Worker node before explicitly activating a scope; no automatic backfill or source switch.
SET NAMES utf8mb4;
SET
time_zone = '+00:00';
CREATE TABLE arte_ai_model_acl_scope
(
    scope_key    char(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '租户和空间 ID 经长度前缀编码后的 SHA-256 摘要；读取时核对原始范围',
    tenant_id    varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '明确迁移的执行租户；不改写历史 ExecutionOwner',
    workspace_id varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '明确迁移的执行空间；没有记录才沿用旧授权，不能删除记录回退',
    payload      longtext                                               NOT NULL COMMENT '封闭迁移清单 JSON：范围、单一配置来源、精确 binding 集合、版本、变更主体和时间；空模型集合表示全部拒绝',
    version      bigint                                                 NOT NULL COMMENT '迁移清单版本，首次为 1；后续更新通过 CAS 递增，不提供取消迁移或回退 LEGACY 接口',
    PRIMARY KEY (scope_key),
    CHECK (version >= 1),
    FOREIGN KEY (tenant_id, workspace_id) REFERENCES arte_platform_workspace (tenant_id, id)
) ENGINE=InnoDB COMMENT='模型公共 ACL 迁移权威：按执行空间明确切换，已迁移范围禁止旧 Grant 兜底';
