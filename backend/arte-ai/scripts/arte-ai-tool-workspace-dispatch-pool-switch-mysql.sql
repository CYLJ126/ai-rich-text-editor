-- 从仅本地文本工具切换到“本地文本工具 + 工作空间名称与说明更新工具”的开发环境调度清单。
-- 使用前停止新受理及所有连接本库的应用／Worker；备份下方 SELECT 返回的原调度池记录。
-- 应用配置须同步改为新清单，并保持 arte.ai-new-tool-write.enabled=true：
-- 80247d79a981503ef33677823d7a0f89983e667850e93ceb36b0a43d31985136
-- 本脚本只移除已知旧清单的 default 池配置，启动后由现有程序按新配置重新创建。
-- 不删除或修改工具定义、绑定、审批、调用、业务结果、工作空间及预算记录。
-- 有活动调用、核对任务、许可、队列预留或未完成派发时拒绝重置；须先按原流程完成／核对。
-- 已终态且无租约、无预留的派发通知可以保留，清单匹配后由 Worker 正常领取并确认，不重新执行工具。
-- 终态集合与 Invocation.State.terminal() 一致；UNKNOWN 历史事实保留，仍在自动核对的任务另行阻止切换。
-- 客户端必须遇错停止；不要使用 --force。发生错误时 ROLLBACK 并关闭当前连接。

START TRANSACTION;

SELECT pool_key, policy_digest, manifest_digest
FROM arte_ai_dispatch_pool
WHERE pool_key = 'default'
    FOR UPDATE;

DELETE
FROM arte_ai_dispatch_pool
WHERE pool_key = 'default'
  AND manifest_digest = 'a6bb1c11e6f08fd0088d121a7cf6e5a6c7e755c1fc03cad73d252a5dacfea92a'
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

-- 必须为 1，才表示旧池已移除；0 表示没有变更，须核对旧清单及上述空闲条件，不要直接放宽检查。
SELECT ROW_COUNT() AS reset_pool_rows;

COMMIT;
