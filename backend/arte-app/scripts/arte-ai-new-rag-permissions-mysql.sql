-- 新聊天文章检索的显式授权；在应用实际连接的 MySQL 库中手动执行。
-- 前置：安全桥、新聊天/RAG 表和普通聊天应用许可已初始化。
-- 默认值对应当前配置及用户 ID=1；配置不同时先修改下面参数。
-- 只补缺失许可，不恢复已撤销的许可，不移动资源，不授权其他用户或整个文章库。
-- 步骤 1 补模型应用的 resource.read；步骤 2 仅授权指定的自有文章用于 AI 处理和外发。
-- @rag_article_id 默认 NULL，因此默认不新增任何文章级授权；填入核对后的文章 ID 再执行。
-- 如需多篇文章，分别指定 ID 执行；分享文章由管理员另行核对现有阅读权限后授权。
-- 授权后的文章仍受现有阅读权限、空间归属、版本和发送前外发确认约束。

set @rag_user_id = 1;
set @rag_tenant_id = 'personal-1';
set @rag_workspace_id = 'workspace-1';
set @rag_application_id = 'ai-new-model';
set @rag_binding_id = 'default-model';
set @rag_article_id = NULL;

-- 1. 仅为已有正常成员、已有启用 AI 应用许可补充文章读取动作。
insert into arte_security_application_policy
(tenant_id, workspace_id, application_id, binding_id, action_code, application_enabled, binding_enabled, revision)
select @rag_tenant_id, @rag_workspace_id, @rag_application_id, @rag_binding_id, 'resource.read', true, true, 1
from arte_rbac_user u
         join arte_security_member m on m.user_id = u.id
where u.id = @rag_user_id
  and u.status = '1'
  and m.tenant_id = @rag_tenant_id
  and m.workspace_id = @rag_workspace_id
  and m.enabled = true
  and exists (select 1 from arte_security_application_policy p
              where p.tenant_id = @rag_tenant_id and p.workspace_id = @rag_workspace_id
                and p.application_id = @rag_application_id and p.binding_id = @rag_binding_id
                and p.action_code = 'resource.ai_process' and p.application_enabled = true and p.binding_enabled = true)
  and not exists (select 1 from arte_security_application_policy p
                  where p.tenant_id = @rag_tenant_id and p.workspace_id = @rag_workspace_id
                    and p.application_id = @rag_application_id and p.binding_id = @rag_binding_id
                    and p.action_code = 'resource.read');

-- 2. 仅指定自有文章；旧所有者权限不隐含 AI 使用或外发许可，因此需逐项登记。
insert into arte_security_resource_grant
(resource_type, resource_id, user_id, action_code, enabled, revision)
select 'ARTICLE', concat('', a.id), u.id, actions.action_code, true, 1
from arte_rt_article a
         join arte_rbac_user u on u.user_name = a.create_by
         join arte_security_member m on m.user_id = u.id
         join arte_security_resource r on r.resource_type = 'ARTICLE' and r.resource_id = concat('', a.id)
         cross join (select 'resource.ai_process' as action_code union all select 'resource.egress') actions
where a.id = @rag_article_id
  and a.is_delete = 0
  and u.id = @rag_user_id
  and u.status = '1'
  and m.tenant_id = @rag_tenant_id and m.workspace_id = @rag_workspace_id and m.enabled = true
  and r.tenant_id = @rag_tenant_id and r.workspace_id = @rag_workspace_id and r.enabled = true
  and exists (select 1 from arte_security_application_policy p
              where p.tenant_id = @rag_tenant_id and p.workspace_id = @rag_workspace_id
                and p.application_id = @rag_application_id and p.binding_id = @rag_binding_id
                and p.action_code = 'resource.read' and p.application_enabled = true and p.binding_enabled = true)
  and exists (select 1 from arte_security_application_policy p
              where p.tenant_id = @rag_tenant_id and p.workspace_id = @rag_workspace_id
                and p.application_id = @rag_application_id and p.binding_id = @rag_binding_id
                and p.action_code = actions.action_code and p.application_enabled = true and p.binding_enabled = true)
  and not exists (select 1 from arte_security_resource_grant g
                  where g.resource_type = 'ARTICLE' and g.resource_id = concat('', a.id)
                    and g.user_id = u.id and g.action_code = actions.action_code);

-- 核对应用许可，以及所指定文章的许可；false 的既有记录须单独核对撤权原因。
select action_code, application_enabled, binding_enabled, revision
from arte_security_application_policy
where tenant_id = @rag_tenant_id and workspace_id = @rag_workspace_id
  and application_id = @rag_application_id and binding_id = @rag_binding_id;

select resource_id, action_code, enabled, revision
from arte_security_resource_grant
where resource_type = 'ARTICLE' and resource_id = concat('', @rag_article_id) and user_id = @rag_user_id;

-- 当前空间的自有文章 ID（只查元数据），用于选择下一篇需要授权的文章。
select a.id
from arte_rt_article a
         join arte_rbac_user u on u.user_name = a.create_by
         join arte_security_resource r on r.resource_type = 'ARTICLE' and r.resource_id = concat('', a.id)
where u.id = @rag_user_id and u.status = '1' and a.is_delete = 0
  and r.tenant_id = @rag_tenant_id and r.workspace_id = @rag_workspace_id and r.enabled = true
order by a.id;
