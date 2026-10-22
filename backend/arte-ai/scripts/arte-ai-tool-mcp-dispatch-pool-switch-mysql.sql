-- 开发环境：将当前已更新 manifest、但 policy_digest 仍旧的 default 调度池切换到 MCP 工具清单。
-- 2026-10-22 只读核对时，以下旧摘要匹配且无活动调用、派发、许可、队列预留或核对任务。
-- 执行前必须停止新受理及所有连接本库的应用／Worker，再备份下方 SELECT 返回的原记录。
-- app.properties 须使用本次完整清单，所有消费者的调度配置必须一致：
-- manifest_digest = a2f49d6886bea4d3b971b6c54271310140d5484a99e5f643629b33168ab26419
-- 当前配置计算的 policy_digest = d7d3d102b0fca2d4bfa4973f46345f1e87b46ec36db85c4ecab9502ce2392fba
-- 不手填新摘要：仅在旧记录及空闲条件均匹配时移除池配置，由应用在下一次调度池访问时重建。
-- 不删除或修改工具、绑定、审批、调用、业务结果和预算记录；不重放历史工具调用。
-- 客户端必须遇错停止，禁止 --force；错误时 ROLLBACK 并关闭连接。

START TRANSACTION;

SELECT pool_key, policy_digest, manifest_digest
FROM arte_ai_dispatch_pool
WHERE pool_key = 'default'
    FOR UPDATE;

DELETE
FROM arte_ai_dispatch_pool
WHERE pool_key = 'default'
  AND policy_digest = '7c82300cff8167ffe8d50db81488704f1773ea2f66e9c8a7040813eddb50b12c'
  AND manifest_digest = 'a2f49d6886bea4d3b971b6c54271310140d5484a99e5f643629b33168ab26419'
  AND NOT EXISTS (SELECT 1
                  FROM arte_ai_invocation
                  WHERE COALESCE(JSON_UNQUOTE(JSON_EXTRACT(workspace_snapshot, '$.body.state')), '')
                            NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'INTERRUPTED', 'UNKNOWN'))
  AND NOT EXISTS (SELECT 1
                  FROM arte_ai_outbox o
                           LEFT JOIN arte_ai_invocation i ON i.id_key = o.invocation_key
                  WHERE o.kind = 'DISPATCH'
                    AND (o.queue_pending <> 0 OR o.queue_reserved <> 0
                      OR (o.delivered = 0 AND (
                          o.finished <> 1
                              OR o.lease_until > CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
                              OR i.id_key IS NULL
                              OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(i.workspace_snapshot, '$.body.state')), '')
                              NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'INTERRUPTED', 'UNKNOWN')
                          ))))
  AND NOT EXISTS (SELECT 1 FROM arte_ai_scheduling_permit)
  AND NOT EXISTS (SELECT 1 FROM arte_ai_dispatch_permit)
  AND NOT EXISTS (SELECT 1 FROM arte_ai_queue_capacity WHERE reservations <> 0)
  AND NOT EXISTS (SELECT 1 FROM arte_ai_tool_outcome_check WHERE state = 'CHECKING');

-- 预期为 1；0 表示未修改，先核对记录及空闲条件，不要去掉保护条件。
SELECT ROW_COUNT() AS reset_pool_rows;

COMMIT;

-- 统一重启后的只读复核：池首次被访问后，应由应用写入上述两个新摘要。
SELECT pool_key, policy_digest, manifest_digest
FROM arte_ai_dispatch_pool
WHERE pool_key = 'default';
