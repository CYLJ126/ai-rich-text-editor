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
