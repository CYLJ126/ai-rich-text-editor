-- M4 只读菜单分配核对，不修改菜单、关系或业务授权。
-- 在目标应用库人工执行；本次编码未连接业务库。
-- 1. 复用既有父／子菜单；需 status=1 且 show_flag='1'，检查同码／同路径冲突。
SELECT menu_code, menu_name, menu_url, father_id, status, show_flag
FROM arte_rbac_menu
WHERE menu_code IN ('Environment', 'EnvironmentWorkspaces', 'EnvironmentModels', 'aiModels', 'ChatNew')
   OR menu_url IN ('/Environment', '/Environment/Workspaces', '/Environment/Models', '/AI/Models', '/AI/ChatNew')
ORDER BY menu_url, menu_code;

-- 2. 原模型／聊天角色及直接用户的菜单分配：用于发现搬迁后缺失的既有入口。
-- 有模型／聊天菜单不表示有空间治理资格；不得据此自动增加业务动作或授予治理权限。
SELECT DISTINCT binding.source,
                binding.binding_type,
                EXISTS (SELECT 1
                        FROM arte_rbac_relation parent
                        WHERE parent.source = binding.source
                          AND parent.binding_type = binding.binding_type
                          AND parent.target = 'Environment')                   AS has_environment,
                EXISTS (SELECT 1
                        FROM arte_rbac_relation workspace_menu
                        WHERE workspace_menu.source = binding.source
                          AND workspace_menu.binding_type = binding.binding_type
                          AND workspace_menu.target = 'EnvironmentWorkspaces') AS has_workspace_menu
FROM arte_rbac_relation binding
WHERE binding.binding_type IN ('role_to_menu', 'user_to_menu')
  AND binding.target IN ('EnvironmentModels', 'aiModels', 'ChatNew')
ORDER BY binding.binding_type, binding.source;
-- 实际治理资格继续以目标空间 getConfiguration.canManage 和写入检查为准。
-- 缺失菜单由菜单管理员按既有角色职责分配现有 Environment / EnvironmentWorkspaces。
-- 查看受影响角色详情刷新角色缓存，再核对 /arte/auth/onlineInfo.menus。
