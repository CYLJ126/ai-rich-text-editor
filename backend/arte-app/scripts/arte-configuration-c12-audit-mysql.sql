-- C12 只读核对。在目标应用库用只读账号执行，再按实际职责人工核对分配。
SELECT menu.id,
       menu.menu_code,
       menu.menu_name,
       menu.menu_url,
       menu.status,
       menu.show_flag,
       menu.father_id,
       parent.menu_code AS parent_code
FROM arte_rbac_menu menu
         LEFT JOIN arte_rbac_menu parent ON parent.id = menu.father_id
WHERE menu.menu_code IN ('Environment', 'EnvironmentTenants', 'EnvironmentWorkspaces', 'EnvironmentModels',
                         'EnvironmentTools', 'aiModels', 'ChatNew')
   OR menu.menu_url IN ('/Environment', '/Environment/Tenants', '/Environment/Workspaces',
                        '/Environment/Models', '/Environment/Tools', '/AI/Models', '/AI/ChatNew',
                        '/Workspace/Policies', '/AI/ModelSharing', '/AI/Connections', '/AI/ModelPlans', '/AI/Tools')
ORDER BY menu.menu_url, menu.menu_code, menu.id;

SELECT menu_code, operation_code, status
FROM arte_rbac_menu_operation
WHERE menu_code = 'EnvironmentTenants'
  AND operation_code IN ('create', 'update')
ORDER BY operation_code;

-- 返回原始关系，含停用或不再满足实际职责的绑定。在线权限以刷新后的 onlineInfo 和服务端业务检查为准。
SELECT DISTINCT source, binding_type, target
FROM arte_rbac_relation
WHERE (binding_type IN ('role_to_menu', 'user_to_menu')
    AND target IN ('Environment', 'EnvironmentTenants', 'EnvironmentWorkspaces', 'EnvironmentModels',
                   'EnvironmentTools', 'aiModels', 'ChatNew'))
   OR (binding_type IN ('role_to_operation', 'user_to_operation')
    AND target IN ('EnvironmentTenants:create', 'EnvironmentTenants:update'))
ORDER BY binding_type, source, target;

SELECT table_name, engine
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('arte_platform_principal_identity', 'arte_platform_tenant', 'arte_platform_tenant_member',
                     'arte_platform_workspace', 'arte_platform_workspace_member', 'arte_platform_workspace_preference',
                     'arte_platform_personal_policy_template', 'arte_platform_membership_invitation',
                     'arte_platform_command', 'arte_platform_audit', 'arte_platform_outbox',
                     'arte_ai_model_resource', 'arte_ai_model_publication', 'arte_ai_workspace_configuration',
                     'arte_ai_workspace_budget_rule', 'arte_ai_account')
ORDER BY table_name;
