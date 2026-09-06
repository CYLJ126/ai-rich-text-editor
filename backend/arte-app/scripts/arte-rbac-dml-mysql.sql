SET NAMES utf8mb4;

-- arte_rbac_menu
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description, show_flag, row_version, create_by, create_time, update_by, update_time) VALUES (1, 'Administration', 'Administration', 'administration', '/Administration', null, 4, 1, '系统管理', '1', 1, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description, show_flag, row_version, create_by, create_time, update_by, update_time) VALUES (2, 'BasicWriting', 'Basic Writing', 'smile', '/Writing/BasicWriting', 9, 1, 1, '基础写作管理', '1', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description, show_flag, row_version, create_by, create_time, update_by, update_time) VALUES (3, 'Chatbot', 'Chatbot', 'robot', '/AI/ChatManagement', null, 8, 1, 'AI 问答', '1', 1, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description, show_flag, row_version, create_by, create_time, update_by, update_time) VALUES (4, 'HomePage', 'HomePage', 'homePage', '/HomePage', null, 1, 1, '首页', '1', 1, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description, show_flag, row_version, create_by, create_time, update_by, update_time) VALUES (5, 'Learn', 'Learn', 'book', '/Learn', null, 6, 1, '学习', '1', 1, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description, show_flag, row_version, create_by, create_time, update_by, update_time) VALUES (6, 'UserManagement', 'User Management', null, '/Administration/UserManagement', 1, 1, 1, '用户管理', '1', 1, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description, show_flag, row_version, create_by, create_time, update_by, update_time) VALUES (7, 'MenuManagement', 'Menu Management', null, '/Administration/MenuManagement', 1, 1, 1, '菜单管理', '1', 1, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description, show_flag, row_version, create_by, create_time, update_by, update_time) VALUES (8, 'RoleManagement', 'Role Management', null, '/Administration/RoleManagement', 1, 1, 1, '角色管理', '1', 1, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description, show_flag, row_version, create_by, create_time, update_by, update_time) VALUES (9, 'Writing', 'Writing', 'write', '/Writing', null, 5, 1, '写作管理目录', '1', 0, 'system', sysdate(), 'system', sysdate());
-- arte_rbac_menu_operation
-- arte_rbac_relation
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (1, 'admin', 'admin', 'user_to_role', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (2, 'admin', 'HomePage', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (3, 'admin', 'Learn', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (4, 'admin', 'Administration', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (5, 'admin', 'UserManagement', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (6, 'admin', 'MenuManagement', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (7, 'admin', 'RoleManagement', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (8, 'admin', 'Chatbot', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (9, 'admin', 'BasicWriting', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (10, 'admin', 'Writing', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (11, 'admin', 'user:list', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (12, 'admin', 'user:update', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (13, 'admin', 'menu:add', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (14, 'admin', 'menu:list', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (15, 'admin', 'user:add', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (16, 'admin', 'user:delete', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (17, 'admin', 'tag:list', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (18, 'admin', 'tag:add', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (19, 'admin', 'tag:update', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (20, 'admin', 'tag:delete', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (21, 'admin', 'tagRelation:add', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (22, 'admin', 'tagRelation:delete', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (23, 'admin', 'tagRelation:list', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (24, 'admin', 'menu:update', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (25, 'admin', 'menu:delete', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (26, 'admin', 'menuOperation:add', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (27, 'admin', 'menuOperation:list', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (28, 'admin', 'menuOperation:update', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (29, 'admin', 'summary:add', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (30, 'admin', 'summary:list', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (31, 'admin', 'summary:format', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (32, 'admin', 'website:list', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (33, 'admin', 'website:refresh', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (34, 'admin', 'sticky:list', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (35, 'admin', 'sticky:add', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (36, 'admin', 'sticky:update', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (37, 'admin', 'sticky:delete', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (38, 'admin', 'role:add', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (39, 'admin', 'role:delete', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (40, 'admin', 'role:list', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (41, 'admin', 'role:update', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (42, 'admin', 'StickyNote', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (43, 'admin', 'TagManagement', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (44, 'admin', 'Personal', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (45, 'admin', 'CacheTest', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (46, 'admin', 'TextFormatter', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time) VALUES (47, 'admin', 'Tools', 'role_to_menu', 'system', sysdate());


-- arte_rbac_role
INSERT INTO arte_rbac_role (id, role_code, role_name, status, description, create_by, update_by, create_time, update_time, row_version) VALUES (1, 'admin', '管理员', '1', '拥有系统管理相关权限', 'system', 'system', sysdate(), sysdate(), 0);
INSERT INTO arte_rbac_role (id, role_code, role_name, status, description, create_by, update_by, create_time, update_time, row_version) VALUES (2, 'ascetic', '修者', '1', '游客', 'system', 'system', sysdate(), sysdate(), 0);
-- arte_rbac_user
INSERT INTO arte_rbac_user (id, user_name, status, mobile, email, password, description, create_by, update_by, create_time, update_time, row_version) VALUES (1, 'admin', '1', '', '', '$2a$10$XqyDPLzYsyRjNmPur/CZnOxoGZUT12xfZ1/wlhHMWBTNoOjfR0nae', null, 'system', 'system', sysdate(), sysdate(), 0);
