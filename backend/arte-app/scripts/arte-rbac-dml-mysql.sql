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
INSERT INTO arte_rbac_menu (id, menu_code, menu_name, icon, menu_url, father_id, order_id, status, description,
                            show_flag, row_version, create_by, create_time, update_by, update_time)
VALUES (35, 'PdfHandler', 'Pdf Handler', null, '/Tools/PdfHandler', 15, 2, 1, 'PDF处理', '1', 1, 'zhangsc',
        '2026-09-11 12:31:16', 'zhangsc', '2026-09-11 12:31:16');

-- arte_rbac_menu_operation
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (3, 'user', 'list', '用户列表', 1, '查看用户列表', 0, 'zhangsc', '2026-03-10 17:40:28', 'zhangsc',
        '2026-03-10 17:40:28');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (4, 'user', 'update', '修改用户', 1, '修改用户信息', 0, 'zhangsc', '2026-03-10 17:40:28', 'zhangsc',
        '2026-03-10 17:40:28');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (5, 'user', 'add', '新增用户', 1, '新增用户', 0, 'zhangsc', '2026-03-10 17:40:29', 'zhangsc',
        '2026-03-10 17:40:29');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (6, 'user', 'delete', '删除用户', 1, '删除用户', 0, 'zhangsc', '2026-03-10 17:40:29', 'zhangsc',
        '2026-03-10 17:40:29');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (7, 'steps', 'list', '步骤列表', 1, '查看步骤列表', 0, 'zhangsc', '2026-03-10 17:40:29', 'zhangsc',
        '2026-03-10 17:40:29');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (8, 'steps', 'update', '修改步骤', 1, '修改步骤信息', 0, 'zhangsc', '2026-03-10 17:40:29', 'zhangsc',
        '2026-03-10 17:40:29');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (9, 'steps', 'add', '新增步骤', 1, '新增步骤', 0, 'zhangsc', '2026-03-10 17:40:30', 'zhangsc',
        '2026-03-10 17:40:30');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (10, 'steps', 'delete', '删除步骤', 1, '删除步骤', 0, 'zhangsc', '2026-03-10 17:40:30', 'zhangsc',
        '2026-03-10 17:40:30');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (11, 'weeklyDay', 'list', '每天进度列表', 1, '查看每天进度列表', 0, 'zhangsc', '2026-03-10 17:40:30', 'zhangsc',
        '2026-03-10 17:40:30');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (12, 'weeklyDay', 'update', '修改每天进度', 1, '修改每天进度信息', 0, 'zhangsc', '2026-03-10 17:40:30',
        'zhangsc', '2026-03-10 17:40:30');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (13, 'weeklyWork', 'update', '修改周课', 1, '修改周课内容', 0, 'zhangsc', '2026-03-10 17:40:31', 'zhangsc',
        '2026-03-10 17:40:31');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (14, 'weeklyWork', 'statistics', '周课统计', 1, '统计周课数据', 0, 'zhangsc', '2026-03-10 17:40:31', 'zhangsc',
        '2026-03-10 17:40:31');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (15, 'weeklyWork', 'list', '周课列表', 1, '查看周课列表', 0, 'zhangsc', '2026-03-10 17:40:31', 'zhangsc',
        '2026-03-10 17:40:31');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (16, 'weeklyWork', 'delete', '删除周课', 1, '删除周课记录', 0, 'zhangsc', '2026-03-10 17:40:32', 'zhangsc',
        '2026-03-10 17:40:32');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (17, 'weeklyWork', 'add', '新增周课', 1, '新增周课记录', 0, 'zhangsc', '2026-03-10 17:40:32', 'zhangsc',
        '2026-03-10 17:40:32');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (18, 'weeklyWork', 'summary', '周课汇总', 1, '汇总周课数据', 0, 'zhangsc', '2026-03-10 17:40:32', 'zhangsc',
        '2026-03-10 17:40:32');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (19, 'tag', 'list', '标签列表', 1, '查看标签列表', 0, 'zhangsc', '2026-03-10 17:40:32', 'zhangsc',
        '2026-03-10 17:40:32');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (20, 'tag', 'add', '新增标签', 1, '新增标签', 0, 'zhangsc', '2026-03-10 17:40:33', 'zhangsc',
        '2026-03-10 17:40:33');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (21, 'tag', 'update', '修改标签', 1, '修改标签信息', 0, 'zhangsc', '2026-03-10 17:40:33', 'zhangsc',
        '2026-03-10 17:40:33');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (22, 'tag', 'delete', '删除标签', 1, '删除标签', 0, 'zhangsc', '2026-03-10 17:40:33', 'zhangsc',
        '2026-03-10 17:40:33');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (23, 'tagRelation', 'add', '新增标签关联', 1, '新增标签关联关系', 0, 'zhangsc', '2026-03-10 17:40:34', 'zhangsc',
        '2026-03-10 17:40:34');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (24, 'tagRelation', 'delete', '删除标签关联', 1, '删除标签关联关系', 0, 'zhangsc', '2026-03-10 17:40:34',
        'zhangsc', '2026-03-10 17:40:34');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (25, 'tagRelation', 'list', '标签关联列表', 1, '查看标签关联列表', 0, 'zhangsc', '2026-03-10 17:40:34',
        'zhangsc', '2026-03-10 17:40:34');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (26, 'dailyWork', 'add', '新增日课', 1, '新增日课记录', 0, 'zhangsc', '2026-03-10 17:40:34', 'zhangsc',
        '2026-03-10 17:40:34');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (27, 'dailyWork', 'update', '修改日课', 1, '修改日课记录', 0, 'zhangsc', '2026-03-10 17:40:35', 'zhangsc',
        '2026-03-10 17:40:35');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (28, 'dailyWork', 'list', '日课列表', 1, '查看日课列表', 0, 'zhangsc', '2026-03-10 17:40:35', 'zhangsc',
        '2026-03-10 17:40:35');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (29, 'dailyWork', 'delete', '删除日课', 1, '删除日课记录', 0, 'zhangsc', '2026-03-10 17:40:35', 'zhangsc',
        '2026-03-10 17:40:35');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (30, 'dailyWork', 'summary', '日课汇总', 1, '汇总日课数据', 0, 'zhangsc', '2026-03-10 17:40:36', 'zhangsc',
        '2026-03-10 17:40:36');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (31, 'todoWork', 'add', '新增待办工作', 1, '新增待办工作记录', 0, 'zhangsc', '2026-03-10 17:40:36', 'zhangsc',
        '2026-03-10 17:40:36');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (32, 'todoWork', 'update', '修改待办工作', 1, '修改待办工作记录', 0, 'zhangsc', '2026-03-10 17:40:36', 'zhangsc',
        '2026-03-10 17:40:36');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (33, 'todoWork', 'list', '待办工作列表', 1, '查看待办工作列表', 0, 'zhangsc', '2026-03-10 17:40:36', 'zhangsc',
        '2026-03-10 17:40:36');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (34, 'todoWork', 'delete', '删除待办工作', 1, '删除待办工作记录', 0, 'zhangsc', '2026-03-10 17:40:37', 'zhangsc',
        '2026-03-10 17:40:37');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (35, 'menu', 'add', '新增菜单', 1, '新增菜单', 0, 'zhangsc', '2026-03-10 17:40:37', 'zhangsc',
        '2026-03-10 17:40:37');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (36, 'menu', 'update', '修改菜单', 1, '修改菜单信息', 0, 'zhangsc', '2026-03-10 17:40:37', 'zhangsc',
        '2026-03-10 17:40:37');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (37, 'menu', 'list', '菜单列表', 1, '查看菜单列表', 0, 'zhangsc', '2026-03-10 17:40:38', 'zhangsc',
        '2026-03-10 17:40:38');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (38, 'menu', 'delete', '删除菜单', 1, '删除菜单', 0, 'zhangsc', '2026-03-10 17:40:38', 'zhangsc',
        '2026-03-10 17:40:38');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (39, 'menuOperation', 'add', '新增菜单操作', 1, '新增菜单操作权限', 0, 'zhangsc', '2026-03-10 17:40:38',
        'zhangsc', '2026-03-10 17:40:38');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (40, 'menuOperation', 'list', '菜单操作列表', 1, '查看菜单操作列表', 0, 'zhangsc', '2026-03-10 17:40:38',
        'zhangsc', '2026-03-10 17:40:38');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (41, 'menuOperation', 'update', '修改菜单操作', 1, '修改菜单操作权限', 0, 'zhangsc', '2026-03-10 17:40:39',
        'zhangsc', '2026-03-10 17:40:39');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (42, 'menuOperation', 'delete', '删除菜单操作', 1, '删除菜单操作权限', 0, 'zhangsc', '2026-03-10 17:40:39',
        'zhangsc', '2026-03-10 17:40:39');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (43, 'summary', 'add', '新增总结', 1, '新增总结记录', 0, 'zhangsc', '2026-03-10 17:40:39', 'zhangsc',
        '2026-03-10 17:40:39');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (44, 'summary', 'list', '总结列表', 1, '查看总结列表', 0, 'zhangsc', '2026-03-10 17:40:40', 'zhangsc',
        '2026-03-10 17:40:40');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (45, 'summary', 'format', '总结格式化', 1, '格式化总结数据', 0, 'zhangsc', '2026-03-10 17:40:40', 'zhangsc',
        '2026-03-10 17:40:40');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (46, 'website', 'list', '网站列表', 1, '查看网站列表', 0, 'zhangsc', '2026-03-10 17:40:40', 'zhangsc',
        '2026-03-10 17:40:40');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (47, 'website', 'refresh', '刷新网站', 1, '刷新网站数据', 0, 'zhangsc', '2026-03-10 17:40:40', 'zhangsc',
        '2026-03-10 17:40:40');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (48, 'timeTrace', 'list', '每日留痕列表', 1, '查看每日留痕记录', 0, 'zhangsc', '2026-03-10 17:40:41', 'zhangsc',
        '2026-03-10 17:40:41');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (49, 'timeTrace', 'add', '新增每日留痕', 1, '新增每日留痕记录', 0, 'zhangsc', '2026-03-10 17:40:41', 'zhangsc',
        '2026-03-10 17:40:41');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (50, 'timeTrace', 'update', '修改每日留痕', 1, '修改每日留痕记录', 0, 'zhangsc', '2026-03-10 17:40:41',
        'zhangsc', '2026-03-10 17:40:41');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (51, 'timeTrace', 'delete', '删除每日留痕', 1, '删除每日留痕记录', 0, 'zhangsc', '2026-03-10 17:40:42',
        'zhangsc', '2026-03-10 17:40:42');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (52, 'sticky', 'list', '便笺列表', 1, '查看便笺列表', 0, 'zhangsc', '2026-03-10 17:40:42', 'zhangsc',
        '2026-03-10 17:40:42');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (53, 'sticky', 'add', '新增便笺', 1, '新增便笺', 0, 'zhangsc', '2026-03-10 17:40:42', 'zhangsc',
        '2026-03-10 17:40:42');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (54, 'sticky', 'update', '修改便笺', 1, '修改便笺内容', 0, 'zhangsc', '2026-03-10 17:40:42', 'zhangsc',
        '2026-03-10 17:40:42');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (55, 'sticky', 'delete', '删除便笺', 1, '删除便笺', 0, 'zhangsc', '2026-03-10 17:40:43', 'zhangsc',
        '2026-03-10 17:40:43');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (56, 'weeklyRegularActivity', 'list', '每周每周常规活动', 1, '查看每周常规活动列表', 0, 'zhangsc',
        '2026-03-10 17:40:43', 'zhangsc', '2026-03-10 17:40:43');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (57, 'weeklyRegularActivity', 'add', '新增每周常规活动', 1, '新增每周常规活动', 0, 'zhangsc',
        '2026-03-10 17:40:43', 'zhangsc', '2026-03-10 17:40:43');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (58, 'weeklyRegularActivity', 'update', '修改每周常规活动', 1, '修改每周常规活动', 0, 'zhangsc',
        '2026-03-10 17:40:44', 'zhangsc', '2026-03-10 17:40:44');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (59, 'weeklyRegularActivity', 'delete', '删除每周常规活动', 1, '删除每周常规活动', 0, 'zhangsc',
        '2026-03-10 17:40:44', 'zhangsc', '2026-03-10 17:40:44');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (60, 'standardField', 'list', '标准字段列表', 1, '查看标准字段列表', 0, 'zhangsc', '2026-03-10 17:40:44',
        'zhangsc', '2026-03-10 17:40:44');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (61, 'standardField', 'add', '新增标准字段', 1, '新增标准字段', 0, 'zhangsc', '2026-03-10 17:40:44', 'zhangsc',
        '2026-03-10 17:40:44');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (62, 'standardField', 'update', '修改标准字段', 1, '修改标准字段信息', 0, 'zhangsc', '2026-03-10 17:40:45',
        'zhangsc', '2026-03-10 17:40:45');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (63, 'standardField', 'delete', '删除标准字段', 1, '删除标准字段', 0, 'zhangsc', '2026-03-10 17:40:45',
        'zhangsc', '2026-03-10 17:40:45');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (64, 'role', 'add', '新增角色', 1, '新增角色', 0, 'zhangsc', '2026-03-10 17:40:29', 'zhangsc',
        '2026-03-10 17:40:29');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (65, 'role', 'delete', '删除角色', 1, '删除角色', 0, 'zhangsc', '2026-03-10 17:40:29', 'zhangsc',
        '2026-03-10 17:40:29');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (66, 'role', 'list', '用户角色', 1, '查看角色列表', 0, 'zhangsc', '2026-03-10 17:40:28', 'zhangsc',
        '2026-03-10 17:40:28');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (67, 'role', 'update', '修改角色', 1, '修改角色信息', 0, 'zhangsc', '2026-03-10 17:40:28', 'zhangsc',
        '2026-03-10 17:40:28');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (70, 'summary', 'update', '更新总结', 1, '更新总结记录', 0, 'zhangsc', '2026-07-29 10:24:22', 'zhangsc',
        '2026-07-29 10:24:22');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (73, 'user', 'export', '导出用户列表', 1, '导出用户列表', 0, 'zhangsc', '2026-08-31 22:14:27', 'zhangsc',
        '2026-08-31 22:14:27');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (74, 'role', 'export', '导出角色列表', 1, '导出角色列表', 0, 'zhangsc', '2026-08-31 22:21:25', 'zhangsc',
        '2026-08-31 22:21:25');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (75, 'menu', 'export', '导出菜单列表', 1, '导出菜单列表', 0, 'zhangsc', '2026-08-31 22:21:59', 'zhangsc',
        '2026-08-31 22:21:59');
INSERT INTO arte_rbac_menu_operation (id, menu_code, operation_code, operation_name, status, description, row_version,
                                      create_by, create_time, update_by, update_time)
VALUES (76, 'pdf', 'handle', 'PDF处理', 1, 'PDF处理', 0, 'zhangsc', '2026-09-11 12:42:49', 'zhangsc',
        '2026-09-11 12:42:49');

    
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
