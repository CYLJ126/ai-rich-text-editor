-- 在 ARTE RBAC 数据库执行。可重复执行；菜单权限只控制入口展示，AI scope 仍由后端授权。
SET NAMES utf8mb4;
START TRANSACTION;

INSERT INTO arte_rbac_menu
(menu_code, menu_name, icon, menu_url, father_id, order_id, status, description,
 show_flag, row_version, create_by, create_time, update_by, update_time)
SELECT 'ChatNew',
       'AI 聊天（新版）',
       'robot',
       '/AI/ChatNew',
       NULL,
       7,
       1,
       '新 AI 会话、模型配置及操作恢复',
       '1',
       0,
       'system',
       CURRENT_TIMESTAMP,
       'system',
       CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM arte_rbac_menu WHERE menu_code = 'ChatNew' OR menu_url = '/AI/ChatNew');

-- 默认只给已存在的 admin 角色展示入口，其他角色通过菜单管理显式分配。
INSERT INTO arte_rbac_relation (source, target, binding_type, create_by, create_time)
SELECT 'admin',
       'ChatNew',
       'role_to_menu',
       'system',
       CURRENT_TIMESTAMP WHERE EXISTS (SELECT 1 FROM arte_rbac_role WHERE role_code = 'admin')
  AND EXISTS (SELECT 1 FROM arte_rbac_menu WHERE menu_code = 'ChatNew' AND menu_url = '/AI/ChatNew')
  AND NOT EXISTS (SELECT 1 FROM arte_rbac_relation
                  WHERE source = 'admin' AND target = 'ChatNew' AND binding_type = 'role_to_menu');

COMMIT;
