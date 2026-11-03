-- M2 全新数据草稿与凭据：在模型资源及公共 ACL 后安装；不回填或自动启用运行模型。
SET NAMES utf8mb4;
CREATE INDEX idx_model_resource_workspace ON arte_ai_model_resource (tenant_id, workspace_id, resource_key);
CREATE TABLE arte_ai_model_draft
(
    resource_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定模型资源身份摘要，与模型资源表主键一致；关联租户、model 资源类型及资源 ID，不作为授权依据',
    version      BIGINT                                         NOT NULL COMMENT '草稿乐观锁版本，首次保存为 1，后续保存通过 CAS 递增；独立于模型管理版本、发布版本和凭据版本',
    payload LONGTEXT NOT NULL COMMENT '草稿固定结构 JSON：资源引用、租户及主空间范围、名称、精确 planRef、受信方案快照、收紧的上下文与输出上限、版本及更新时间；不含密钥或已发布定义',
    PRIMARY KEY (resource_key),
    CHECK (version >= 1),
    CONSTRAINT fk_model_draft_resource FOREIGN KEY (resource_key) REFERENCES arte_ai_model_resource (resource_key)
) ENGINE=InnoDB COMMENT='实际模型配置草稿：保存待验证和发布的配置，修改草稿不覆盖已有发布或自动授予执行资格';

CREATE TABLE arte_ai_model_credential
(
    resource_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联的稳定模型资源身份摘要；每个实际模型保存一份当前凭据记录，种类由归属固定，凭据责任与资源归属分别核验',
    version      BIGINT                                         NOT NULL COMMENT '凭据乐观锁版本，首次提供为 1，替换或首次撤销通过 CAS 递增；用于绑定加密附加认证数据及使旧验证结果失效',
    payload      LONGTEXT                                       NOT NULL COMMENT '凭据固定结构 JSON：资源及空间范围、SecretRef、责任主体、加密密钥 ID、带算法版本前缀的 SM4-GCM 密文、固定凭据种类、撤销标记、版本及更新时间；不保存明文，撤销后密文为空',
    PRIMARY KEY (resource_key),
    CHECK (version >= 1),
    CONSTRAINT fk_model_credential_resource FOREIGN KEY (resource_key) REFERENCES arte_ai_model_resource (resource_key)
) ENGINE=InnoDB COMMENT='实际模型加密凭据：独立记录提供者维护责任及当前凭据代次，不因模型分享或空间治理角色自动转交维护资格';

CREATE TABLE arte_ai_model_validation
(
    id           VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '服务器生成的验证请求 ID，用于原子领取、完成及结果查询；不作为访问令牌或模型执行 ID',
    resource_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '本次验证关联的稳定模型资源身份摘要；同一模型可以保留多次独立验证记录',
    version      BIGINT                                                 NOT NULL COMMENT '验证记录乐观锁版本，登记 PENDING 时为 0，领取、完成或标记失效时通过 CAS 递增',
    payload LONGTEXT NOT NULL COMMENT '验证固定结构 JSON：请求 ID、资源及空间范围、请求主体、草稿与凭据版本、精确 planRef、完整方案摘要、连接摘要、状态及安全结果码、处理期限、检查时间和版本；不含密钥或远端响应正文',
    PRIMARY KEY (id),
    CHECK (version >= 0),
    KEY          idx_model_validation_resource(resource_key),
    CONSTRAINT fk_model_validation_resource FOREIGN KEY (resource_key) REFERENCES arte_ai_model_resource (resource_key)
) ENGINE=InnoDB COMMENT='实际模型连接验证记录：耐久保存受理、领取、完成及失效状态，同键重试不重复已领取探测，验证成功不自动发布';
