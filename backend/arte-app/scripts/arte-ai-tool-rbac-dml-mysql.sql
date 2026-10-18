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
