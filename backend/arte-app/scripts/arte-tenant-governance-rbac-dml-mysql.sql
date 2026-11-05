-- 先安装 EnvironmentTenants 菜单。仅登记操作项，由管理员另行显式授权。
INSERT INTO arte_rbac_menu_operation
(menu_code, operation_code, operation_name, status, description, create_by, create_time)
SELECT menu.menu_code,
       wanted.operation_code,
       wanted.operation_name,
       1,
       wanted.description,
       'system',
       CURRENT_TIMESTAMP
FROM (SELECT DISTINCT menu_code FROM arte_rbac_menu WHERE menu_code = 'EnvironmentTenants') menu
         CROSS JOIN (SELECT 'update'                             AS operation_code,
                            '维护租户名称'                       AS operation_name,
                            '另须当前目标租户负责人或管理员资格' AS description
                     UNION ALL
                     SELECT 'create', '创建组织租户', '另须部署开启组织创建并安装版本化政策模板') wanted
WHERE NOT EXISTS (SELECT 1
                  FROM arte_rbac_menu_operation existing
                  WHERE existing.menu_code = menu.menu_code
                    AND existing.operation_code = wanted.operation_code);
