-- 4-02：依次执行公共空间、ACL、模型资源、4-01 草稿及凭据 DDL 后，显式执行一次。
-- 既有库还须先执行 arte-ai-model-personal-source-migration-mysql.sql；应用不自动迁移，不激活旧空间。
CREATE TABLE arte_ai_model_personal_publication
(
    binding_key  char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户与精确 bindingRef 的不可逆索引键，与模型版本行一致',
    scope_key    char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户及主空间的不可逆索引键，限定运行目录范围',
    resource_key char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联稳定模型资源索引键，历史版本共用原身份及 ACL',
    digest       char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可变发布附加证据的规范 JSON SHA-256 摘要，读取时核验',
    payload      longtext                                       NOT NULL COMMENT '固定绑定、审核目录修订、模板摘要、草稿和凭据代次、验证 ID、输出上限及发布者时间；不含明文或密文密钥',
    PRIMARY KEY (binding_key),
    KEY          ix_personal_model_scope(scope_key,binding_key),
    KEY          ix_personal_model_resource(resource_key,binding_key),
    FOREIGN KEY (binding_key) REFERENCES arte_ai_model_version (binding_key),
    FOREIGN KEY (resource_key) REFERENCES arte_ai_model_resource (resource_key)
) ENGINE=InnoDB COMMENT='个人模型不可变发布证据：供同空间目录及凭据用途门控使用，不创建预算或调用';
