SET NAMES utf8mb4;

-- arte_rbac_menu
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description,
                            show_flag, row_version, create_by, create_time, update_by, update_time)
VALUES (1, 'Administration', 'Administration', 'SettingOutlined', '/Administration', null, 4, 1, '系统管理', '1', 1,
        'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description,
                            show_flag, row_version, create_by, create_time, update_by, update_time)
VALUES (2, 'BasicWriting', 'Basic Writing', 'SmileOutlined', '/Writing/BasicWriting', 9, 1, 1, '基础写作管理', '1', 0,
        'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description,
                            show_flag, row_version, create_by, create_time, update_by, update_time)
VALUES (3, 'Chatbot', 'Chatbot', 'RobotOutlined', '/AI/ChatManagement', null, 8, 1, 'AI 问答', '1', 1, 'system',
        sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description,
                            show_flag, row_version, create_by, create_time, update_by, update_time)
VALUES (4, 'HomePage', 'HomePage', 'HomeOutlined', '/HomePage', null, 1, 1, '首页', '1', 1, 'system', sysdate(),
        'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description,
                            show_flag, row_version, create_by, create_time, update_by, update_time)
VALUES (6, 'UserManagement', 'User Management', null, '/Administration/UserManagement', 1, 1, 1, '用户管理', '1', 1,
        'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description,
                            show_flag, row_version, create_by, create_time, update_by, update_time)
VALUES (7, 'MenuManagement', 'Menu Management', null, '/Administration/MenuManagement', 1, 1, 1, '菜单管理', '1', 1,
        'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description,
                            show_flag, row_version, create_by, create_time, update_by, update_time)
VALUES (8, 'RoleManagement', 'Role Management', null, '/Administration/RoleManagement', 1, 1, 1, '角色管理', '1', 1,
        'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description,
                            show_flag, row_version, create_by, create_time, update_by, update_time)
VALUES (5, 'Personal', 'Personal', 'userOutlined', '/Personal', null, 2, 1, '个人管理', '1', 1, 'system', sysdate(),
        'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description,
                            show_flag, row_version, create_by, create_time, update_by, update_time)
VALUES (33, 'TagManagement', 'Tag Management', null, '/Personal/TagManagement', 5, 4, 1, '标签管理', '1', 1, 'system',
        sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description,
                            show_flag, row_version, create_by, create_time, update_by, update_time)
VALUES (9, 'Writing', 'Writing', 'SignatureOutlined', '/Writing', null, 5, 1, '写作管理目录', '1', 0, 'system',
        sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description,
                            show_flag, row_version, create_by, create_time, update_by, update_time)
VALUES (15, 'Tools', 'Tools', 'ToolOutlined', '/Tools', null, 3, 1, '工具', '1', 1, 'system', sysdate(), 'system',
        sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description,
                            show_flag, row_version, create_by, create_time, update_by, update_time)
VALUES (16, 'TextFormatter', 'Text Formatter', null, '/Tools/TextFormatter', 15, 1, 1, '文本格式化', '1', 1, 'system',
        sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description,
                            show_flag, row_version, create_by, create_time, update_by, update_time)
VALUES (35, 'PdfHandler', 'Pdf Handler', null, '/Tools/PdfHandler', 15, 2, 1, 'PDF处理', '1', 1, 'system', sysdate(),
        'system', sysdate());
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description,
                            show_flag, row_version, create_by, create_time, update_by, update_time)
VALUES (34, 'StickyNote', 'Sticky Note', null, '/Personal/StickyNote', 5, 5, 1, '便笺管理', '1', 1, 'system', sysdate(),
        'system', sysdate());


-- arte_rbac_menu_operation
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (3, 'user', 'list', '用户列表', 1, '查看用户列表', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (4, 'user', 'update', '修改用户', 1, '修改用户信息', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (5, 'user', 'add', '新增用户', 1, '新增用户', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (6, 'user', 'delete', '删除用户', 1, '删除用户', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (19, 'tag', 'list', '标签列表', 1, '查看标签列表', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (20, 'tag', 'add', '新增标签', 1, '新增标签', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (21, 'tag', 'update', '修改标签', 1, '修改标签信息', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (22, 'tag', 'delete', '删除标签', 1, '删除标签', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (23, 'tagRelation', 'add', '新增标签关联', 1, '新增标签关联关系', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (24, 'tagRelation', 'delete', '删除标签关联', 1, '删除标签关联关系', 0, 'system', sysdate(), 'system',
        sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (25, 'tagRelation', 'list', '标签关联列表', 1, '查看标签关联列表', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (35, 'menu', 'add', '新增菜单', 1, '新增菜单', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (36, 'menu', 'update', '修改菜单', 1, '修改菜单信息', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (37, 'menu', 'list', '菜单列表', 1, '查看菜单列表', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (38, 'menu', 'delete', '删除菜单', 1, '删除菜单', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (39, 'menuOperation', 'add', '新增菜单操作', 1, '新增菜单操作权限', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (40, 'menuOperation', 'list', '菜单操作列表', 1, '查看菜单操作列表', 0, 'system', sysdate(), 'system',
        sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (41, 'menuOperation', 'update', '修改菜单操作', 1, '修改菜单操作权限', 0, 'system', sysdate(), 'system',
        sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (42, 'menuOperation', 'delete', '删除菜单操作', 1, '删除菜单操作权限', 0, 'system', sysdate(), 'system',
        sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (43, 'summary', 'add', '新增总结', 1, '新增总结记录', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (44, 'summary', 'list', '总结列表', 1, '查看总结列表', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (45, 'summary', 'format', '总结格式化', 1, '格式化总结数据', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (46, 'website', 'list', '网站列表', 1, '查看网站列表', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (47, 'website', 'refresh', '刷新网站', 1, '刷新网站数据', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (52, 'sticky', 'list', '便笺列表', 1, '查看便笺列表', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (53, 'sticky', 'add', '新增便笺', 1, '新增便笺', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (54, 'sticky', 'update', '修改便笺', 1, '修改便笺内容', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (55, 'sticky', 'delete', '删除便笺', 1, '删除便笺', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (64, 'role', 'add', '新增角色', 1, '新增角色', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (65, 'role', 'delete', '删除角色', 1, '删除角色', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (66, 'role', 'list', '用户角色', 1, '查看角色列表', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (67, 'role', 'update', '修改角色', 1, '修改角色信息', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (70, 'summary', 'update', '更新总结', 1, '更新总结记录', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (73, 'user', 'export', '导出用户列表', 1, '导出用户列表', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (74, 'role', 'export', '导出角色列表', 1, '导出角色列表', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (75, 'menu', 'export', '导出菜单列表', 1, '导出菜单列表', 0, 'system', sysdate(), 'system', sysdate());
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (76, 'pdf', 'handle', 'PDF处理', 1, 'PDF处理', 0, 'system', sysdate(), 'system', sysdate());


-- arte_rbac_relation
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (1, 'admin', 'admin', 'user_to_role', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (2, 'admin', 'HomePage', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (3, 'admin', 'Learn', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (4, 'admin', 'Administration', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (5, 'admin', 'UserManagement', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (6, 'admin', 'MenuManagement', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (7, 'admin', 'RoleManagement', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (8, 'admin', 'Chatbot', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (10, 'admin', 'Writing', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (11, 'admin', 'user:list', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (12, 'admin', 'user:update', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (13, 'admin', 'menu:add', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (14, 'admin', 'menu:list', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (15, 'admin', 'user:add', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (16, 'admin', 'user:delete', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (17, 'admin', 'tag:list', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (18, 'admin', 'tag:add', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (19, 'admin', 'tag:update', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (20, 'admin', 'tag:delete', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (21, 'admin', 'tagRelation:add', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (22, 'admin', 'tagRelation:delete', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (23, 'admin', 'tagRelation:list', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (24, 'admin', 'menu:update', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (25, 'admin', 'menu:delete', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (26, 'admin', 'menuOperation:add', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (27, 'admin', 'menuOperation:list', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (28, 'admin', 'menuOperation:update', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (29, 'admin', 'summary:add', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (30, 'admin', 'summary:list', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (31, 'admin', 'summary:format', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (32, 'admin', 'website:list', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (33, 'admin', 'website:refresh', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (34, 'admin', 'sticky:list', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (35, 'admin', 'sticky:add', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (36, 'admin', 'sticky:update', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (37, 'admin', 'sticky:delete', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (38, 'admin', 'role:add', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (39, 'admin', 'role:delete', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (40, 'admin', 'role:list', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (41, 'admin', 'role:update', 'role_to_operation', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (42, 'admin', 'StickyNote', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (43, 'admin', 'TagManagement', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (44, 'admin', 'Personal', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (45, 'admin', 'CacheTest', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (46, 'admin', 'TextFormatter', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (47, 'admin', 'Tools', 'role_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (48, 'admin', 'PdfHandler', 'user_to_menu', 'system', sysdate());
INSERT INTO arte_rbac_relation (id, source, target, binding_type, create_by, create_time)
VALUES (49, 'admin', 'pdf:handle', 'user_to_operation', 'system', sysdate());


-- arte_rbac_role
INSERT INTO arte_rbac_role (id, role_code, role_name, status, description, create_by, update_by, create_time,
                            update_time, row_version)
VALUES (1, 'admin', '管理员', '1', '拥有系统管理相关权限', 'system', 'system', sysdate(), sysdate(), 0);
INSERT INTO arte_rbac_role (id, role_code, role_name, status, description, create_by, update_by, create_time,
                            update_time, row_version)
VALUES (2, 'ascetic', '修者', '1', '游客', 'system', 'system', sysdate(), sysdate(), 0);
-- arte_rbac_user
INSERT INTO arte_rbac_user (id, user_name, status, mobile, email, password, description, create_by, update_by,
                            create_time, update_time, row_version)
VALUES (1, 'admin', '1', '', '', '$2a$10$XqyDPLzYsyRjNmPur/CZnOxoGZUT12xfZ1/wlhHMWBTNoOjfR0nae', null, 'system',
        'system', sysdate(), sysdate(), 0);

-- 新版 AI 聊天入口（已有数据库使用 20261011-ai-chat-new-menu.sql）
INSERT INTO arte_rbac_menu (menu_code, menu_name, icon, menu_url, father_id, order_id, status, description, show_flag,
                            row_version, create_by, create_time, update_by, update_time)
SELECT 'ChatNew',
       'AI 聊天（新版）',
       'MessageOutlined',
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

-- AI TOOL S1-08: 仅登记操作定义，不授予角色／用户，不创建前端菜单。
-- 新环境执行 RBAC 基线 DML 已包含此段；已有环境执行本脚本。
-- 部署时暂停 RBAC 定义写入；已有同码定义（包括停用状态）原样保留。

INSERT INTO arte_rbac_menu_operation
(menu_code, operation_code, operation_name, status, description, row_version,
 create_by, create_time, update_by, update_time)
SELECT 'aiTool',
       'list',
       '查看 AI 工具',
       1,
       '查看已发布工具目录及本人绑定',
       0,
       'system',
       CURRENT_TIMESTAMP,
       'system',
       CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM arte_rbac_menu_operation
                  WHERE menu_code = 'aiTool' AND operation_code = 'list');

INSERT INTO arte_rbac_menu_operation
(menu_code, operation_code, operation_name, status, description, row_version,
 create_by, create_time, update_by, update_time)
SELECT 'aiTool',
       'invoke',
       '使用 AI 工具',
       1,
       '管理本人绑定；实际执行入口待后续批次',
       0,
       'system',
       CURRENT_TIMESTAMP,
       'system',
       CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM arte_rbac_menu_operation
                  WHERE menu_code = 'aiTool' AND operation_code = 'invoke');

INSERT INTO arte_rbac_menu_operation
(menu_code, operation_code, operation_name, status, description, row_version,
 create_by, create_time, update_by, update_time)
SELECT 'aiTool',
       'manage',
       '管理 AI 工具',
       1,
       '管理全局工具目录和发布，另需 aiConfiguration:manage',
       0,
       'system',
       CURRENT_TIMESTAMP,
       'system',
       CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM arte_rbac_menu_operation
                  WHERE menu_code = 'aiTool' AND operation_code = 'manage');

INSERT INTO arte_rbac_menu_operation
(menu_code, operation_code, operation_name, status, description, row_version,
 create_by, create_time, update_by, update_time)
SELECT 'aiConfiguration',
       'manage',
       '管理 AI 配置',
       1,
       '管理平台 AI 配置；与工具治理权限共同校验',
       0,
       'system',
       CURRENT_TIMESTAMP,
       'system',
       CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM arte_rbac_menu_operation
                  WHERE menu_code = 'aiConfiguration' AND operation_code = 'manage');

INSERT INTO arte_rbac_menu_operation
(menu_code, operation_code, operation_name, status, description, row_version,
 create_by, create_time, update_by, update_time)
SELECT 'aiTool',
       'approve',
       '审批 AI 工具调用',
       1,
       '仅按当前空间成员与固定审批名单决定工具调用；不授予执行资格',
       0,
       'system',
       CURRENT_TIMESTAMP,
       'system',
       CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM arte_rbac_menu_operation
                  WHERE menu_code = 'aiTool' AND operation_code = 'approve');


-- S3-05：核对资格不是审批、账单或普通调用权，不由 admin 标记隐式替代。
INSERT INTO arte_rbac_menu_operation
(menu_code, operation_code, operation_name, status, description, row_version, create_by, create_time, update_by,
 update_time)
SELECT 'aiTool',
       'reconcile',
       '核对 AI 工具业务结果',
       1,
       '当前空间资源治理与独立证据核验；不能执行工具或修改费用',
       0,
       'system',
       CURRENT_TIMESTAMP,
       'system',
       CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM arte_rbac_menu_operation WHERE menu_code='aiTool' AND operation_code='reconcile');

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
                            '工作空间目录、信息、偏好与目标空间 AI 配置'
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

-- ER-5：只登记独立 ai:budget:admin 操作定义，不授予用户、角色或具体账户权限。
-- menu_code 与 operation_code 拼接后的完整操作码为 ai:budget:admin。
-- 已存在定义（包括停用状态）原样保留；具体账户还需公共 budget-account:reconcile 授权。
INSERT INTO arte_rbac_menu_operation
(menu_code, operation_code, operation_name, status, description, row_version, create_by, create_time, update_by,
 update_time)
SELECT 'ai',
       'budget:admin',
       '核对 AI 账户费用',
       1,
       '需真实账单及具体账户核对授权；不含指派、聊天、密钥或额度修改权限',
       0,
       'system',
       CURRENT_TIMESTAMP,
       'system',
       CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM arte_rbac_menu_operation WHERE menu_code='ai' AND operation_code='budget:admin');
