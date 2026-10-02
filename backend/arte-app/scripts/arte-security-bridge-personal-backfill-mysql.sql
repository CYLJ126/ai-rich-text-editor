-- 在 DDL 后执行。仅映射正常账号、已有自有资料；不会创建分享成员或 AI／外发许可。
-- 可重复执行，INSERT IGNORE 保留已经撤销的成员关系／已经迁移的资源映射。
INSERT
IGNORE INTO arte_security_member (tenant_id, workspace_id, user_id, enabled, revision)
SELECT CONCAT('personal-', id), CONCAT('workspace-', id), id, TRUE, 1
FROM arte_rbac_user
WHERE status = '1';

INSERT
IGNORE INTO arte_security_resource (resource_type, resource_id, tenant_id, workspace_id, enabled, revision)
SELECT 'ARTICLE', CAST(a.id AS CHAR), CONCAT('personal-', u.id), CONCAT('workspace-', u.id), TRUE, 1
FROM arte_rt_article a
         JOIN arte_rbac_user u ON u.user_name = a.create_by
WHERE a.is_delete = 0
  AND u.status = '1';

INSERT
IGNORE INTO arte_security_resource (resource_type, resource_id, tenant_id, workspace_id, enabled, revision)
SELECT 'CATALOG', CAST(c.id AS CHAR), CONCAT('personal-', u.id), CONCAT('workspace-', u.id), TRUE, 1
FROM arte_rt_catalog c
         JOIN arte_rbac_user u ON u.user_name = c.create_by
WHERE c.is_delete = 0
  AND u.status = '1';
