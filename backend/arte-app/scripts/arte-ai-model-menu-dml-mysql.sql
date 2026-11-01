-- 独立模型管理入口：复用 /AI/Models，与新版聊天并列显示在应用顶部 Tab。
-- 已有同码／同路径（含停用状态）原样保留；执行前暂停菜单写入并核对冲突。
-- SQL 不刷新 Redis 用户／角色权限缓存；重启后端或重新登录也不会清除这些缓存。
-- 执行后须刷新受影响的权限缓存，再重载页面；角色可在角色管理中查看详情，触发数据库重读并刷新该角色缓存。
-- 若菜单已返回但页面不展示，核对 /arte/auth/onlineInfo 的 menus 是否包含 aiModels；勿清空整个 Redis。
INSERT INTO arte_rbac_menu
(menu_code, menu_name, icon, menu_url, father_id, order_id, status, description, show_flag, row_version,
 create_by, create_time, update_by, update_time)
SELECT 'aiModels',
       '模型管理',
       'AppstoreOutlined',
       '/AI/Models',
       NULL,
       8,
       1,
       '当前空间模型创建、草稿、个人凭据、验证与发布',
       '1',
       0,
       'system',
       CURRENT_TIMESTAMP,
       'system',
       CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM arte_rbac_menu WHERE menu_code = 'aiModels' OR menu_url = '/AI/Models');

-- 菜单可见性沿用新版聊天已分配的角色／用户关系；不增加模型动作、凭据或预算授权。
-- 重复执行不重复插入关系；后续角色／用户分配仍通过菜单管理维护。
INSERT INTO arte_rbac_relation (source, target, binding_type, create_by, create_time)
SELECT DISTINCT binding.source, 'aiModels', binding.binding_type, 'system', CURRENT_TIMESTAMP
FROM arte_rbac_relation binding
WHERE binding.target = 'ChatNew'
  AND binding.binding_type IN ('role_to_menu', 'user_to_menu')
  AND EXISTS (SELECT 1
              FROM arte_rbac_menu
              WHERE menu_code = 'aiModels'
                AND menu_url = '/AI/Models'
                AND status = 1
                AND show_flag = '1')
  AND EXISTS (SELECT 1
              FROM arte_rbac_menu
              WHERE menu_code = 'ChatNew'
                AND menu_url = '/AI/ChatNew'
                AND status = 1)
  AND NOT EXISTS (SELECT 1
                  FROM arte_rbac_relation existing
                  WHERE existing.source = binding.source
                    AND existing.target = 'aiModels'
                    AND existing.binding_type = binding.binding_type);
