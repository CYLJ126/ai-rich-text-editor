-- S2-07：仅登记现有 RBAC 菜单，不自动授予角色／用户或开启 TOOL。
-- 已有同码／同路径（含停用状态）原样保留；执行前暂停菜单写入并核对冲突。
INSERT INTO arte_rbac_menu
(menu_code, menu_name, icon, menu_url, father_id, order_id, status, description, show_flag, row_version,
 create_by, create_time, update_by, update_time)
SELECT 'aiTool',
       'AI 工具',
       'FunctionOutlined',
       '/AI/Tools',
       NULL,
       8,
       1,
       '工具目录及本人空间配置',
       '1',
       0,
       'system',
       CURRENT_TIMESTAMP,
       'system',
       CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM arte_rbac_menu WHERE menu_code = 'aiTool' OR menu_url = '/AI/Tools');
