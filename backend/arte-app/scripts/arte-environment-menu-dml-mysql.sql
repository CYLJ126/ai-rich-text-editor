-- 环境配置菜单：只组织现有页面，不新增租户管理、工具实现或业务操作权限。
-- 已有环境执行本脚本；新环境的 arte-rbac-dml-mysql.sql 已包含相同内容。
-- 在目标应用库执行（如 arte）；先暂停并发菜单维护，核对同码／同路径冲突。
-- 可复用已手工登记的 Environment /Environment 顶级菜单；不覆盖已有菜单或启停状态。
-- 仅向已存在的 admin 角色分配父／子菜单，其他角色与用户通过菜单管理分配。
-- 执行后在“角色管理”查看 admin 详情以刷新角色缓存，再刷新当前登录信息和页面。
-- SQL 不会清除 Redis 缓存；重启或重新登录不能替代权限缓存刷新。
SET NAMES utf8mb4;

INSERT INTO arte_rbac_menu
(menu_code, menu_name, icon, menu_url, father_id, order_id, status, description, show_flag, row_version,
 create_by, create_time, update_by, update_time)
SELECT 'Environment',
       '环境配置',
       'ControlOutlined',
       '/Environment',
       NULL,
       5,
       1,
       '租户、工作空间、模型、工具及其他环境资源配置',
       '1',
       0,
       'system',
       CURRENT_TIMESTAMP,
       'system',
       CURRENT_TIMESTAMP WHERE NOT EXISTS (
    SELECT 1 FROM arte_rbac_menu WHERE menu_code = 'Environment' OR menu_url = '/Environment'
);

INSERT INTO arte_rbac_menu
(menu_code, menu_name, icon, menu_url, father_id, order_id, status, description, show_flag, row_version,
 create_by, create_time, update_by, update_time)
SELECT entry.menu_code,
       entry.menu_name,
       entry.icon,
       entry.menu_url,
       parent.id,
       entry.order_id,
       1,
       entry.description,
       '1',
       0,
       'system',
       CURRENT_TIMESTAMP,
       'system',
       CURRENT_TIMESTAMP
FROM arte_rbac_menu parent
         CROSS JOIN (SELECT 'EnvironmentTenants'     AS menu_code,
                            '租户配置'               AS menu_name,
                            'ApartmentOutlined'      AS icon,
                            '/Environment/Tenants'   AS menu_url,
                            1                        AS order_id,
                            '已有租户及空间政策配置' AS description
                     UNION ALL
                     SELECT 'EnvironmentWorkspaces',
                            '工作空间配置',
                            'AppstoreOutlined',
                            '/Environment/Workspaces',
                            2,
                            '工作空间选择、创建、信息维护与个人偏好'
                     UNION ALL
                     SELECT 'EnvironmentModels',
                            '模型配置',
                            'RobotOutlined',
                            '/Environment/Models',
                            3,
                            '现有模型管理、草稿、凭据、验证及发布'
                     UNION ALL
                     SELECT 'EnvironmentTools',
                            '工具配置',
                            'ToolOutlined',
                            '/Environment/Tools',
                            4,
                            '现有工具目录、本人空间配置及调用') entry
WHERE parent.menu_code = 'Environment'
  AND parent.menu_url = '/Environment'
  AND parent.father_id IS NULL
  AND parent.status = 1
  AND parent.show_flag = '1'
  AND (SELECT COUNT(*)
       FROM arte_rbac_menu
       WHERE menu_code = 'Environment'
          OR menu_url = '/Environment') = 1
  AND NOT EXISTS (SELECT 1
                  FROM arte_rbac_menu existing
                  WHERE existing.menu_code = entry.menu_code
                     OR existing.menu_url = entry.menu_url);

INSERT INTO arte_rbac_relation (source, target, binding_type, create_by, create_time)
SELECT DISTINCT assigned_role.role_code, menu.menu_code, 'role_to_menu', 'system', CURRENT_TIMESTAMP
FROM arte_rbac_role assigned_role
         JOIN arte_rbac_menu parent
              ON parent.menu_code = 'Environment' AND parent.menu_url = '/Environment'
                  AND parent.father_id IS NULL AND parent.status = 1 AND parent.show_flag = '1'
         JOIN arte_rbac_menu menu ON menu.id = parent.id OR menu.father_id = parent.id
         JOIN (SELECT 'Environment' AS menu_code, '/Environment' AS menu_url
               UNION ALL
               SELECT 'EnvironmentTenants', '/Environment/Tenants'
               UNION ALL
               SELECT 'EnvironmentWorkspaces', '/Environment/Workspaces'
               UNION ALL
               SELECT 'EnvironmentModels', '/Environment/Models'
               UNION ALL
               SELECT 'EnvironmentTools', '/Environment/Tools') expected
              ON expected.menu_code = menu.menu_code AND expected.menu_url = menu.menu_url
WHERE assigned_role.role_code = 'admin'
  AND assigned_role.status = 1
  AND menu.status = 1
  AND menu.show_flag = '1'
  AND (SELECT COUNT(*)
       FROM arte_rbac_menu
       WHERE menu_code = 'Environment'
          OR menu_url = '/Environment') = 1
  AND NOT EXISTS (SELECT 1
                  FROM arte_rbac_menu conflict
                  WHERE (conflict.menu_code = menu.menu_code OR conflict.menu_url = menu.menu_url)
                    AND conflict.id <> menu.id)
  AND NOT EXISTS (SELECT 1
                  FROM arte_rbac_relation existing
                  WHERE existing.source = assigned_role.role_code
                    AND existing.target = menu.menu_code
                    AND existing.binding_type = 'role_to_menu');
