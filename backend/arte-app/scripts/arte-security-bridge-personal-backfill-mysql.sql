-- 在安全桥接 DDL 后执行，仅映射正常账号及其已有自有资料。
-- 不创建分享成员、资源动作授权、应用许可或外发许可。
-- 可重复执行；insert ignore 保留已撤销的成员关系及已经迁移的资源归属。

-- 为正常账号补齐个人租户及工作空间成员关系。
insert ignore into arte_security_member (tenant_id, workspace_id, user_id, enabled, revision)
select concat('personal-', id), concat('workspace-', id), id, true, 1
from arte_rbac_user
where status = '1';

-- 按现有创建人映射未删除文章的归属；找不到正常创建人时不写入。
insert ignore into arte_security_resource (resource_type, resource_id, tenant_id, workspace_id, enabled, revision)
select 'ARTICLE', cast(a.id as char), concat('personal-', u.id), concat('workspace-', u.id), true, 1
from arte_rt_article a
         join arte_rbac_user u on u.user_name = a.create_by
where a.is_delete = 0
  and u.status = '1';

-- 按现有创建人映射未删除目录的归属；既有团队归属保持不变。
insert ignore into arte_security_resource (resource_type, resource_id, tenant_id, workspace_id, enabled, revision)
select 'CATALOG', cast(c.id as char), concat('personal-', u.id), concat('workspace-', u.id), true, 1
from arte_rt_catalog c
         join arte_rbac_user u on u.user_name = c.create_by
where c.is_delete = 0
  and u.status = '1';
