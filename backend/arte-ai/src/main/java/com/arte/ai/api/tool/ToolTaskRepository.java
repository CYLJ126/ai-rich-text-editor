package com.arte.ai.api.tool;

import com.arte.ai.pojo.tool.ToolTask;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 持久化工具任务及其最终结果的存储端口。
 * <p>
 * 实现需提供状态转换的并发保护，防止同一任务被多个 Worker 重复执行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolTaskRepository {

    void saveTask(ToolTask task);

    Optional<ToolTask> findTask(String taskId);

    /**
     * 使用有租约的原子领取避免正常情况下的多 Worker 重复执行。
     * 工具仍需依靠幂等键应对租约过期、网络分区等导致的至少一次执行。
     */
    boolean tryClaim(String taskId, String workerId, Instant leaseUntil);

    boolean renewClaim(String taskId, String workerId, Instant leaseUntil);

    void saveResult(String taskId, ToolResult<? extends ToolResponse> result);

    Optional<ToolResult<? extends ToolResponse>> findResult(String taskId);

    List<ToolTask> findRecoverable(int limit);
}
