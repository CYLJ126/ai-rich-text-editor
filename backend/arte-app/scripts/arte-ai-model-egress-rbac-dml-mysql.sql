-- Assign this operation explicitly to platform security operators.
INSERT INTO arte_rbac_menu_operation
(menu_code, operation_code, operation_name, status, description, row_version,
 create_by, create_time, update_by, update_time)
SELECT 'aiSecurity',
       'manageEgress',
       '管理平台模型出口策略',
       1,
       '读写平台模型 HTTP 出口白名单',
       0,
       'system',
       CURRENT_TIMESTAMP,
       'system',
       CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM arte_rbac_menu_operation
                  WHERE menu_code = 'aiSecurity' AND operation_code = 'manageEgress');
