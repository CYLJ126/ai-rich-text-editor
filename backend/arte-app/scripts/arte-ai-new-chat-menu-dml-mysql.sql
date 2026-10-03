-- 新聊天入口：可重复执行，不覆盖已存在的菜单状态或旧聊天入口。
-- 默认只登记 admin 的菜单可见性；其他角色通过既有 RBAC 管理授权。
-- 菜单许可不授予空间成员、模型应用、外发或预算权限。
insert into arte_rbac_menu
(menu_code, menu_name, icon, menu_url, father_id, order_id, status,
 description, show_flag, row_version, create_by, create_time, update_by, update_time)
select 'AIChat',
       '新聊天',
       'robot',
       '/AI/Chat',
       null,
       10,
       1,
       '新 AI 会话管理',
       '1',
       0,
       'system',
       current_timestamp,
       'system',
       current_timestamp where not exists (select 1 from arte_rbac_menu where menu_code = 'AIChat');

insert into arte_rbac_relation (source, target, binding_type, create_by, create_time)
select 'admin',
       'AIChat',
       'role_to_menu',
       'system',
       current_timestamp where not exists (
    select 1 from arte_rbac_relation
    where source = 'admin' and target = 'AIChat' and binding_type = 'role_to_menu'
);
