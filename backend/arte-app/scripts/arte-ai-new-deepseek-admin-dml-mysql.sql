-- 默认新聊天的数据库初始化；在应用当前实际连接的数据库中手动执行。
-- 前置：已执行 arte-security-bridge-ddl-mysql.sql、arte-execution-support-ddl-mysql.sql、
-- arte-ai-new-model-ddl-mysql.sql、arte-ai-new-chat-ddl-mysql.sql。
-- 与 app.properties 默认值匹配：personal-1 / workspace-1、ai-new-model、default-model、v1、CNY。
-- 仅为正常账号 id=1 初始化，按稳定用户 ID 匹配，不限制用户名（例如 zhangsc）。
-- 若实际用户 ID 或配置不同，先调整配置及本脚本；当前固定作用域及预算键均对应用户 ID=1。
-- 新建预算时累计上限为 10 元 CNY；首次执行前可修改第 6 步的额度。
-- 可重复执行：仅补缺失记录，不重置已撤销的许可、已有额度、预留或消耗。
-- 不保存 API Key，不调用供应商；用户仍需在页面明确确认每次外发。

-- 当前账号检查；必须能查到正常账号（id=1、status=1）。
select id, user_name, status
from arte_rbac_user
where id = 1;

-- 1. 工作空间成员资格（页面初始化的第一个条件）。
insert into arte_security_member (tenant_id, workspace_id, user_id, enabled, revision)
select 'personal-1', 'workspace-1', u.id, true, 1
from arte_rbac_user u
where u.id = 1
  and u.status = '1'
  and not exists (select 1
                  from arte_security_member
                  where tenant_id = 'personal-1'
                    and workspace_id = 'workspace-1'
                    and user_id = 1);

-- 2. 应用及绑定的 AI 使用许可（页面初始化的第二个条件）。
insert into arte_security_application_policy
(tenant_id, workspace_id, application_id, binding_id, action_code, application_enabled, binding_enabled, revision)
select 'personal-1',
       'workspace-1',
       'ai-new-model',
       'default-model',
       'resource.ai_process',
       true,
       true,
       1
from arte_rbac_user u
where u.id = 1
  and u.status = '1'
  and not exists (select 1
                  from arte_security_application_policy
                  where tenant_id = 'personal-1'
                    and workspace_id = 'workspace-1'
                    and application_id = 'ai-new-model'
                    and binding_id = 'default-model'
                    and action_code = 'resource.ai_process');

-- 3. 应用及绑定的外发许可；发送消息时需要。
insert into arte_security_application_policy
(tenant_id, workspace_id, application_id, binding_id, action_code, application_enabled, binding_enabled, revision)
select 'personal-1',
       'workspace-1',
       'ai-new-model',
       'default-model',
       'resource.egress',
       true,
       true,
       1
from arte_rbac_user u
where u.id = 1
  and u.status = '1'
  and not exists (select 1
                  from arte_security_application_policy
                  where tenant_id = 'personal-1'
                    and workspace_id = 'workspace-1'
                    and application_id = 'ai-new-model'
                    and binding_id = 'default-model'
                    and action_code = 'resource.egress');

-- 4. 固定 DeepSeek 连接源站许可。
-- connection_key 由 JdbcSecurityRepository.connectionKey(ResourceRef.saved("ai-connection", "default-model", "v1")) 生成。
insert into arte_security_connection (tenant_id, workspace_id, connection_key, origin, enabled, revision)
select 'personal-1',
       'workspace-1',
       '2558b6d93bc2aa52ca5f706ffc947cd2aed58337fad0a455b78b347aeb18d707',
       'https://api.deepseek.com',
       true,
       1
from arte_rbac_user u
where u.id = 1
  and u.status = '1'
  and not exists (select 1
                  from arte_security_connection
                  where tenant_id = 'personal-1'
                    and workspace_id = 'workspace-1'
                    and connection_key = '2558b6d93bc2aa52ca5f706ffc947cd2aed58337fad0a455b78b347aeb18d707');

-- 5. 只允许固定连接执行 model.generate 用途。
insert into arte_security_egress_rule
(tenant_id, workspace_id, application_id, binding_id, connection_key, purpose, enabled, revision)
select 'personal-1',
       'workspace-1',
       'ai-new-model',
       'default-model',
       '2558b6d93bc2aa52ca5f706ffc947cd2aed58337fad0a455b78b347aeb18d707',
       'model.generate',
       true,
       1
from arte_rbac_user u
where u.id = 1
  and u.status = '1'
  and not exists (select 1
                  from arte_security_egress_rule
                  where tenant_id = 'personal-1'
                    and workspace_id = 'workspace-1'
                    and application_id = 'ai-new-model'
                    and binding_id = 'default-model'
                    and connection_key = '2558b6d93bc2aa52ca5f706ffc947cd2aed58337fad0a455b78b347aeb18d707'
                    and purpose = 'model.generate');

-- 6. 用户 ID=1 的应用侧累计预算上限设为 10 元；可在首次执行前修改此数值。
-- 这是本应用的调用额度控制，不是 DeepSeek 账户余额或充值。
-- scope_key 由 JdbcModelExecutionStore.budgetKey(new ExecutionScope("personal-1", "workspace-1", new PrincipalRef("1", PrincipalType.USER))) 生成。
insert into arte_ai_new_budget (scope_key, amount_limit, reserved_amount, spent_amount, currency, enabled, revision)
select '833dace428dc4e79feefc82d6a3d20c85a854a4b286c52a4f26d949b1acd6d31', 10.00000000, 0, 0, 'CNY', true, 1
from arte_rbac_user u
where u.id = 1
  and u.status = '1'
  and not exists (select 1
                  from arte_ai_new_budget
                  where scope_key = '833dace428dc4e79feefc82d6a3d20c85a854a4b286c52a4f26d949b1acd6d31');

-- 初始化结果；已有记录若为 false，须由管理员单独决定是否重新启用。
select *
from arte_security_member
where tenant_id = 'personal-1'
  and workspace_id = 'workspace-1'
  and user_id = 1;
select *
from arte_security_application_policy
where tenant_id = 'personal-1'
  and workspace_id = 'workspace-1'
  and application_id = 'ai-new-model'
  and binding_id = 'default-model';
select *
from arte_security_connection
where tenant_id = 'personal-1'
  and workspace_id = 'workspace-1'
  and connection_key = '2558b6d93bc2aa52ca5f706ffc947cd2aed58337fad0a455b78b347aeb18d707';
select *
from arte_security_egress_rule
where tenant_id = 'personal-1'
  and workspace_id = 'workspace-1'
  and application_id = 'ai-new-model'
  and binding_id = 'default-model'
  and connection_key = '2558b6d93bc2aa52ca5f706ffc947cd2aed58337fad0a455b78b347aeb18d707';
select *
from arte_ai_new_budget
where scope_key = '833dace428dc4e79feefc82d6a3d20c85a854a4b286c52a4f26d949b1acd6d31';
