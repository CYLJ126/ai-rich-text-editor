-- 第 2、5 节：仅在 02 已成功、所有节点仍停写时补齐模型结构。
-- MySQL DDL 隐式提交，本阶段不宣称整体回滚；不修复未安装的 Tool/搜索/调度增量。
SET
@arte_reset_structure_done = NULL;
DROP PROCEDURE IF EXISTS arte_doc_model_reset_empty;
DROP PROCEDURE IF EXISTS arte_doc_model_reset_structure;
DELIMITER
$$
CREATE PROCEDURE arte_doc_model_reset_empty(IN table_name_arg VARCHAR (128))
    SQL SECURITY INVOKER
BEGIN
    IF
EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = table_name_arg) THEN
        SET @arte_doc_count_sql = CONCAT('SELECT COUNT(*) INTO @arte_doc_count FROM `', table_name_arg, '`');
PREPARE arte_doc_count_stmt FROM @arte_doc_count_sql;
EXECUTE arte_doc_count_stmt;
DEALLOCATE PREPARE arte_doc_count_stmt;
IF
@arte_doc_count <> 0 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Structure repair requires empty reset tables';
END IF;
END IF;
END$$
CREATE PROCEDURE arte_doc_model_reset_structure()
    SQL SECURITY INVOKER
BEGIN
    IF
DATABASE() IS NULL OR @arte_reset_expected_database IS NULL
       OR BINARY DATABASE() <> BINARY @arte_reset_expected_database
       OR @arte_reset_data_done IS NULL OR BINARY @arte_reset_data_done <> BINARY DATABASE()
       OR COALESCE(@arte_reset_confirmation, '') <> 'RESET_NEW_AI_ALL_TENANTS' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Run data reset successfully in this same confirmed database/session first';
END IF;
    IF
COALESCE(@arte_reset_rebuild_source, -1) NOT IN (0, 1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Set rebuild_source explicitly to 0 or 1';
END IF;
    IF
@@foreign_key_checks <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Enable foreign_key_checks before repairing structure';
END IF;
CALL arte_doc_model_reset_empty('arte_ai_model_use_scope');
CALL arte_doc_model_reset_empty('arte_ai_model_sharing');
CALL arte_doc_model_reset_empty('arte_ai_model_publication');
CALL arte_doc_model_reset_empty('arte_ai_model_validation');
CALL arte_doc_model_reset_empty('arte_ai_model_draft');
CALL arte_doc_model_reset_empty('arte_ai_model_credential');
CALL arte_doc_model_reset_empty('arte_ai_model_version');
CALL arte_doc_model_reset_empty('arte_ai_model_resource');
CALL arte_doc_model_reset_empty('arte_ai_model_definition_source');
CALL arte_doc_model_reset_empty('arte_ai_model_acl_scope');
CALL arte_doc_model_reset_empty('arte_ai_budget_definition');
CALL arte_doc_model_reset_empty('arte_ai_workspace_configuration');
CALL arte_doc_model_reset_empty('arte_ai_workspace_budget_rule');
CALL arte_doc_model_reset_empty('arte_ai_control_revision');
IF
EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'arte_ai_control_head') THEN
        IF EXISTS (SELECT 1 FROM arte_ai_control_head WHERE id <> 1 OR version <> 0) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Control head must be empty or (1,0) before structure repair';
END IF;
END IF;
    -- source 旧类型或旧 CHECK 上限：只允许在明确选择且空表时按原 DDL 重建。
    IF
@arte_reset_rebuild_source = 1 THEN
DROP TABLE IF EXISTS arte_ai_model_definition_source;
ELSEIF
EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'arte_ai_model_definition_source') THEN
        IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
                       AND table_name = 'arte_ai_model_definition_source' AND column_name = 'source' AND data_type = 'int')
           OR NOT EXISTS (
               SELECT 1 FROM information_schema.table_constraints tc JOIN information_schema.check_constraints cc
                 ON cc.constraint_schema = tc.constraint_schema AND cc.constraint_name = tc.constraint_name
               WHERE tc.table_schema = DATABASE() AND tc.table_name = 'arte_ai_model_definition_source'
                 AND tc.constraint_type = 'CHECK' AND tc.enforced = 'YES'
                 AND REPLACE(REPLACE(LOWER(cc.check_clause), '`', ''), ' ', '') LIKE '%source>=0%'
                 AND REPLACE(REPLACE(LOWER(cc.check_clause), '`', ''), ' ', '') LIKE '%source<=3%') THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Inspect source schema; set rebuild_source=1 only after confirming empty-table rebuild';
END IF;
END IF;

    -- 当前初始化定义，仅对缺表创建；已存在表不冒充已完成结构升级。
CREATE TABLE IF NOT EXISTS arte_ai_control_revision
(
    version
    bigint
    NOT
    NULL
    COMMENT
    '已发布控制面修订版本，正整数；历史不可覆盖',
    payload
    longtext
    NOT
    NULL
    COMMENT
    '审核连接、独立 reviewedPlans、精确费率、默认开关与费率上限及工具定义的固定结构 JSON；审核方案不生成执行绑定或用户账户',
    published_by
    varchar
(
    256
) NOT NULL COMMENT '发布者稳定主体 ID，供审计关联',
    published_at timestamp
(
    6
) NOT NULL COMMENT '修订发布时间，微秒精度',
    PRIMARY KEY
(
    version
),
    CHECK
(
    version
    >=
    1
)
    ) ENGINE=InnoDB COMMENT='AI 数据库控制面的不可变发布历史';

-- 当前初始化定义，仅对缺表创建；已存在表不冒充已完成结构升级。
CREATE TABLE IF NOT EXISTS arte_ai_control_head
(
    id
    int
    NOT
    NULL
    COMMENT
    '唯一发布指针，固定为 1',
    version
    bigint
    NOT
    NULL
    COMMENT
    '当前发布版本；0 表示尚未显式导入配置',
    PRIMARY
    KEY
(
    id
),
    CHECK
(
    id =
    1
    AND
    version
    >=
    0
)
    ) ENGINE=InnoDB COMMENT='AI 控制面当前发布指针';

-- 当前初始化定义，仅对缺表创建；已存在表不冒充已完成结构升级。
CREATE TABLE IF NOT EXISTS arte_ai_workspace_configuration
(
    tenant_id
    varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公共租户 ID',
    workspace_id varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公共空间 ID，与租户共同限定范围',
    payload longtext NOT NULL COMMENT '实际模型资源与固定版本选择、启用状态及模板来源；预算规则独立保存；不包含历史消息或密钥',
    version bigint NOT NULL COMMENT '空间 AI 配置的乐观锁版本，更新时递增',
    PRIMARY KEY
(
    tenant_id,
    workspace_id
),
    FOREIGN KEY
(
    tenant_id,
    workspace_id
) REFERENCES arte_platform_workspace
(
    tenant_id,
    id
),
    CHECK
(
    version
    >=
    0
)
    ) ENGINE=InnoDB COMMENT='空间独立 AI 设置；模板发布不覆盖已有配置';

-- 当前初始化定义，仅对缺表创建；已存在表不冒充已完成结构升级。
CREATE TABLE IF NOT EXISTS arte_ai_workspace_budget_rule
(
    tenant_id
    varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '规则所属租户',
    workspace_id varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '规则所属目标空间',
    payload longtext NOT NULL COMMENT '独立精确费率批准规则；不保存主体账户或模型授权',
    version bigint NOT NULL COMMENT '规则集合 CAS 版本，与模型配置版本独立',
    PRIMARY KEY
(
    tenant_id,
    workspace_id
),
    FOREIGN KEY
(
    tenant_id,
    workspace_id
) REFERENCES arte_platform_workspace
(
    tenant_id,
    id
),
    CHECK
(
    version
    >=
    0
)
    ) ENGINE=InnoDB COMMENT='目标空间独立预算规则；撤销不删除历史账本';

-- 当前初始化定义，仅对缺表创建；已存在表不冒充已完成结构升级。
CREATE TABLE IF NOT EXISTS arte_ai_budget_definition
(
    budget_ref
    varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '预算唯一引用，按空间、主体及费率确定，不复用旧账户',
    tenant_id varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '预算所属租户 ID',
    workspace_id varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '预算所属空间 ID',
    subject_id varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '账户所属稳定主体 ID；不同主体保持独立账本',
    payload longtext NOT NULL COMMENT '预算定义 JSON；余额、预留和已计费用继续由 arte_ai_account 权威保存',
    version bigint NOT NULL COMMENT '预算定义乐观锁版本，非负',
    PRIMARY KEY
(
    budget_ref
),
    KEY ix_ai_budget_scope
(
    tenant_id,
    workspace_id,
    subject_id
),
    FOREIGN KEY
(
    tenant_id,
    workspace_id
) REFERENCES arte_platform_workspace
(
    tenant_id,
    id
),
    FOREIGN KEY
(
    subject_id
) REFERENCES arte_platform_principal_identity
(
    subject_id
),
    CHECK
(
    version
    >=
    0
)
    ) ENGINE=InnoDB COMMENT='公共空间预算定义，不覆盖既有 AI 历史和账本归属';

-- 当前初始化定义，仅对缺表创建；已存在表不冒充已完成结构升级。
CREATE TABLE IF NOT EXISTS arte_ai_model_resource
(
    resource_key
    char
(
    64
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户 ID、model 资源类型及资源 ID 经长度前缀编码后的 SHA-256 摘要，用作物理主键；读取时仍核对完整资源身份',
    tenant_id varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '模型管理资源所属租户 ID，与资源引用、主空间及载荷中的归属一致',
    resource_id varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '服务器生成的稳定模型管理资源 ID；独立于 bindingRef，新发布版本沿用原资源身份及 ACL',
    workspace_id varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '模型固定管理空间；空间模型亦为归属空间，租户模型真实归属取载荷 ownership，使用范围另行授权',
    payload longtext NOT NULL COMMENT '模型管理元数据的固定结构 JSON：资源引用、归属、名称、创建者、状态、绑定族、版本及创建时间；不含凭据明文或预算账本',
    version bigint NOT NULL COMMENT '管理资源乐观锁版本，初始为 0；关联新发布版本或停用时递增，通过 CAS 更新，与公共 ACL 版本独立',
    PRIMARY KEY
(
    resource_key
),
    CHECK
(
    version
    >=
    0
),
    FOREIGN KEY
(
    tenant_id,
    workspace_id
) REFERENCES arte_platform_workspace
(
    tenant_id,
    id
)
    ) ENGINE=InnoDB COMMENT='稳定模型管理资源：保存空间归属及当前管理状态，创建者、凭据责任与执行归属分别处理';

IF
NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
                   AND table_name = 'arte_ai_model_resource' AND index_name = 'idx_model_resource_workspace') THEN
CREATE INDEX idx_model_resource_workspace ON arte_ai_model_resource (tenant_id, workspace_id, resource_key);
END IF;
    IF
NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
                   AND table_name = 'arte_ai_model_resource' AND index_name = 'ux_ai_model_resource_tenant') THEN
CREATE UNIQUE INDEX ux_ai_model_resource_tenant ON arte_ai_model_resource (resource_key, tenant_id);
END IF;
    IF
NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
                   AND table_name = 'arte_ai_model_resource' AND index_name = 'idx_model_resource_workspace'
                   GROUP BY index_name HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'tenant_id,workspace_id,resource_key')
       OR NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
                      AND table_name = 'arte_ai_model_resource' AND index_name = 'ux_ai_model_resource_tenant'
                      GROUP BY index_name HAVING MAX(non_unique) = 0
                      AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'resource_key,tenant_id') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Existing model resource index names have unexpected column order/uniqueness';
END IF;

    -- 当前初始化定义，仅对缺表创建；已存在表不冒充已完成结构升级。
CREATE TABLE IF NOT EXISTS arte_ai_model_version
(
    binding_key
    char
(
    64
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户 ID 与精确 bindingRef 经长度前缀编码后的 SHA-256 摘要；保证同租户同一发布引用仅映射到一个管理资源',
    resource_key char
(
    64
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联的稳定模型管理资源身份摘要；同一资源可关联绑定族的多个精确发布版本',
    tenant_id varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '映射所属租户 ID；不同租户可经各自明确授权关联同一固定发布引用',
    binding_id varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '精确发布引用中的 binding 标识，关联后须属于管理资源已确定的绑定族，不作为管理资源 ID',
    binding_version varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'binding 的不可变字符串版本，与 binding_id 共同定位精确发布内容；不解析 latest 或覆盖旧版本',
    snapshot_digest char
(
    64
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '资源身份、发布内容摘要、来源、目录修订、releaseRef、关联主体及时间的 SHA-256 摘要；读取时核验历史快照完整性',
    payload longtext NOT NULL COMMENT '不可变版本关联的固定结构 JSON：资源引用、绑定及连接和费率快照、来源、明确目录修订、发布标识、关联主体及时间；仅含凭据引用',
    PRIMARY KEY
(
    binding_key
),
    KEY ix_ai_model_versions
(
    resource_key,
    binding_key
),
    FOREIGN KEY
(
    resource_key
) REFERENCES arte_ai_model_resource
(
    resource_key
),
    FOREIGN KEY
(
    tenant_id
) REFERENCES arte_platform_tenant
(
    id
)
    ) ENGINE=InnoDB COMMENT='模型精确发布版本映射：保留不可变关联及快照，新版本共享稳定管理资源和公共 ACL';

-- 当前初始化定义，仅对缺表创建；已存在表不冒充已完成结构升级。
CREATE TABLE IF NOT EXISTS arte_ai_model_definition_source
(
    definition_key
    char
(
    64
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '定义类型、ID 及精确版本经长度前缀编码后的 SHA-256 摘要；全局唯一，读取冲突行时核对原始元组',
    definition_type varchar
(
    96
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '已登记定义类型；首批为模型 binding 及其 capability、connection、rate 依赖',
    definition_id varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '发布定义的原始 ID，与类型及版本共同确定内容身份，不代表资源归属或使用授权',
    definition_version varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '发布定义的精确字符串版本；同一精确引用不得被不同内容覆盖',
    source int NOT NULL COMMENT '已登记的内容权威来源：0 FIXED 固定部署定义，1 DATABASE 数据库已发布定义，2 PERSONAL 历史个人发布，3 RESOURCE 实际模型资源发布；相同依赖保留原权威',
    content_digest char
(
    64
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '该定义规范化内容的 SHA-256 摘要；能力特性集合排序、数值归一化，用于拒绝同一精确引用的内容冲突',
    PRIMARY KEY
(
    definition_key
),
    CONSTRAINT ck_ai_model_definition_source CHECK
(
    source
    >=
    0
    AND
    source
    <=
    3
)
    ) ENGINE=InnoDB COMMENT='模型发布定义来源登记：固定精确引用的内容权威及摘要，不直接授予模型、凭据或预算权限';

-- 当前初始化定义，仅对缺表创建；已存在表不冒充已完成结构升级。
CREATE TABLE IF NOT EXISTS arte_ai_model_acl_scope
(
    scope_key
    char
(
    64
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户和空间 ID 经长度前缀编码后的 SHA-256 摘要；读取时核对原始范围',
    tenant_id varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '明确迁移的执行租户；不改写历史 ExecutionOwner',
    workspace_id varchar
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '明确迁移的执行空间；没有记录才沿用旧授权，不能删除记录回退',
    payload longtext NOT NULL COMMENT '封闭迁移清单 JSON：范围、单一配置来源、精确 binding 集合、版本、变更主体和时间；空模型集合表示全部拒绝',
    version bigint NOT NULL COMMENT '迁移清单版本，首次为 1；后续更新通过 CAS 递增，不提供取消迁移或回退 LEGACY 接口',
    PRIMARY KEY
(
    scope_key
),
    CHECK
(
    version
    >=
    1
),
    FOREIGN KEY
(
    tenant_id,
    workspace_id
) REFERENCES arte_platform_workspace
(
    tenant_id,
    id
)
    ) ENGINE=InnoDB COMMENT='模型公共 ACL 迁移权威：按执行空间明确切换，已迁移范围禁止旧 Grant 兜底';

-- 当前初始化定义，仅对缺表创建；已存在表不冒充已完成结构升级。
CREATE TABLE IF NOT EXISTS arte_ai_model_draft
(
    resource_key
    CHAR
(
    64
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定模型资源身份摘要，与模型资源表主键一致；关联租户、model 资源类型及资源 ID，不作为授权依据',
    version BIGINT NOT NULL COMMENT '草稿乐观锁版本，首次保存为 1，后续保存通过 CAS 递增；独立于模型管理版本、发布版本和凭据版本',
    payload LONGTEXT NOT NULL COMMENT '草稿固定结构 JSON：资源引用、租户及主空间范围、名称、精确 planRef、受信方案快照、收紧的上下文与输出上限、版本及更新时间；不含密钥或已发布定义',
    PRIMARY KEY
(
    resource_key
),
    CHECK
(
    version
    >=
    1
),
    CONSTRAINT fk_model_draft_resource FOREIGN KEY
(
    resource_key
) REFERENCES arte_ai_model_resource
(
    resource_key
)
    ) ENGINE=InnoDB COMMENT='实际模型配置草稿：保存待验证和发布的配置，修改草稿不覆盖已有发布或自动授予执行资格';

-- 当前初始化定义，仅对缺表创建；已存在表不冒充已完成结构升级。
CREATE TABLE IF NOT EXISTS arte_ai_model_credential
(
    resource_key
    CHAR
(
    64
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联的稳定模型资源身份摘要；每个实际模型保存一份当前凭据记录，种类由归属固定，凭据责任与资源归属分别核验',
    version BIGINT NOT NULL COMMENT '凭据乐观锁版本，首次提供为 1，替换或首次撤销通过 CAS 递增；用于绑定加密附加认证数据及使旧验证结果失效',
    payload LONGTEXT NOT NULL COMMENT '凭据固定结构 JSON：资源及空间范围、SecretRef、责任主体、加密密钥 ID、带算法版本前缀的 SM4-GCM 密文、固定凭据种类、撤销标记、版本及更新时间；不保存明文，撤销后密文为空',
    PRIMARY KEY
(
    resource_key
),
    CHECK
(
    version
    >=
    1
),
    CONSTRAINT fk_model_credential_resource FOREIGN KEY
(
    resource_key
) REFERENCES arte_ai_model_resource
(
    resource_key
)
    ) ENGINE=InnoDB COMMENT='实际模型加密凭据：独立记录提供者维护责任及当前凭据代次，不因模型分享或空间治理角色自动转交维护资格';

-- 当前初始化定义，仅对缺表创建；已存在表不冒充已完成结构升级。
CREATE TABLE IF NOT EXISTS arte_ai_model_validation
(
    id
    VARCHAR
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '服务器生成的验证请求 ID，用于原子领取、完成及结果查询；不作为访问令牌或模型执行 ID',
    resource_key CHAR
(
    64
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本次验证关联的稳定模型资源身份摘要；同一模型可以保留多次独立验证记录',
    version BIGINT NOT NULL COMMENT '验证记录乐观锁版本，登记 PENDING 时为 0，领取、完成或标记失效时通过 CAS 递增',
    payload LONGTEXT NOT NULL COMMENT '验证固定结构 JSON：请求 ID、资源及空间范围、请求主体、草稿与凭据版本、精确 planRef、完整方案摘要、连接摘要、状态及安全结果码、处理期限、检查时间和版本；不含密钥或远端响应正文',
    PRIMARY KEY
(
    id
),
    CHECK
(
    version
    >=
    0
),
    KEY idx_model_validation_resource
(
    resource_key
),
    CONSTRAINT fk_model_validation_resource FOREIGN KEY
(
    resource_key
) REFERENCES arte_ai_model_resource
(
    resource_key
)
    ) ENGINE=InnoDB COMMENT='实际模型连接验证记录：耐久保存受理、领取、完成及失效状态，同键重试不重复已领取探测，验证成功不自动发布';

-- 当前初始化定义，仅对缺表创建；已存在表不冒充已完成结构升级。
CREATE TABLE IF NOT EXISTS arte_ai_model_publication
(
    binding_key
    char
(
    64
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户与精确 bindingRef 的不可逆索引键，与模型版本行一致',
    scope_key char
(
    64
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户及主空间的不可逆索引键，限定运行目录范围',
    resource_key char
(
    64
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联稳定模型资源索引键，历史版本共用原身份及 ACL',
    digest char
(
    64
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可变发布附加证据的规范 JSON SHA-256 摘要，读取时核验',
    payload longtext NOT NULL COMMENT '固定绑定、精确 planRef、方案与连接摘要、审核目录修订、草稿及凭据代次、凭据种类、验证 ID、输出上限及发布者时间；不含明文或密文密钥',
    PRIMARY KEY
(
    binding_key
),
    KEY ix_model_publication_scope
(
    scope_key,
    binding_key
),
    KEY ix_model_publication_resource
(
    resource_key,
    binding_key
),
    FOREIGN KEY
(
    binding_key
) REFERENCES arte_ai_model_version
(
    binding_key
),
    FOREIGN KEY
(
    resource_key
) REFERENCES arte_ai_model_resource
(
    resource_key
)
    ) ENGINE=InnoDB COMMENT='实际模型不可变发布证据：供同空间目录及凭据用途门控使用，不创建预算或调用';

-- 当前初始化定义，仅对缺表创建；已存在表不冒充已完成结构升级。
CREATE TABLE IF NOT EXISTS arte_ai_model_sharing
(
    resource_key
    CHAR
(
    64
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定租户模型摘要',
    tenant_id VARCHAR
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '模型所属租户',
    version BIGINT NOT NULL COMMENT '范围及凭据用途 CAS 版本',
    payload LONGTEXT NOT NULL COMMENT '管理空间、使用空间、凭据代次和绑定成员代次的消费者；无密钥',
    PRIMARY KEY
(
    resource_key
),
    UNIQUE
(
    resource_key,
    tenant_id
),
    CHECK
(
    version
    >=
    0
),
    FOREIGN KEY
(
    resource_key,
    tenant_id
) REFERENCES arte_ai_model_resource
(
    resource_key,
    tenant_id
),
    FOREIGN KEY
(
    tenant_id
) REFERENCES arte_platform_tenant
(
    id
)
    ) ENGINE=InnoDB COMMENT='租户模型独立使用范围与租户凭据用途';

-- 当前初始化定义，仅对缺表创建；已存在表不冒充已完成结构升级。
CREATE TABLE IF NOT EXISTS arte_ai_model_use_scope
(
    resource_key
    CHAR
(
    64
) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '模型摘要',
    tenant_id VARCHAR
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '与模型归属相同的租户',
    workspace_id VARCHAR
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '明确允许使用的空间',
    PRIMARY KEY
(
    resource_key,
    workspace_id
),
    KEY ix_model_use_target
(
    tenant_id,
    workspace_id,
    resource_key
),
    FOREIGN KEY
(
    resource_key,
    tenant_id
) REFERENCES arte_ai_model_sharing
(
    resource_key,
    tenant_id
),
    FOREIGN KEY
(
    tenant_id,
    workspace_id
) REFERENCES arte_platform_workspace
(
    tenant_id,
    id
)
    ) ENGINE=InnoDB COMMENT='运行候选索引，仍核验 ACL 和凭据用途';

INSERT INTO arte_ai_control_head(id, version)
VALUES (1, 0) ON DUPLICATE KEY
UPDATE version = 0;
SET
@arte_reset_structure_done = DATABASE();
SELECT 'MODEL_STRUCTURE_OK' AS result, DATABASE() AS target_database;
END$$
DELIMITER ;
CALL arte_doc_model_reset_structure();
DROP PROCEDURE arte_doc_model_reset_structure;
DROP PROCEDURE arte_doc_model_reset_empty;
