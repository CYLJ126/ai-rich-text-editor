-- Existing workspace installations only. Fresh installs already have this column.
-- Do not infer a template version for existing policies and do not overwrite their rules.
ALTER TABLE arte_platform_tenant_policy
    ADD COLUMN personal_template_version bigint NULL COMMENT '创建时个人模板版本；旧政策／组织为空，后续治理保留来源',
    ADD CHECK (personal_template_version IS NULL OR personal_template_version >= 1);
