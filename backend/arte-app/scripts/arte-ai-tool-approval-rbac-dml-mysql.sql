-- S3-02：只补审批操作定义，不授予角色／用户；保留已有同码定义。
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

