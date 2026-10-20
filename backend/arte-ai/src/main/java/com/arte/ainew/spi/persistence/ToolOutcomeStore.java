package com.arte.ainew.spi.persistence;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.tool.ToolOutcomeCheck;
import com.arte.ainew.pojo.tool.ToolOutcomeEvidence;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 工具结果未知后的后台自动核对存储端口，供 Worker 恢复任务、领取查询工作并提交核对结果。
 * 负责持久化核对进度、次数、时限和租约，并在事务中校验原调用、原 Attempt 及原操作身份。
 * 已证实的业务结果与调用终态原子提交；无法确认的结果按既定限额继续核对或转人工处理。
 * 本端口仅供系统收尾使用，不重新执行工具，也不承担用户人工核对命令或预算结算职责。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 10:48 ✾
 */
public interface ToolOutcomeStore {

    /**
     * 有界扫描历史 UNKNOWN 工具调用，为尚未登记的原调用补建核对任务，并持久化扫描进度。
     * 不重置已有任务的次数或时限；返回本批补建的任务数量。
     */
    Mono<Integer> recoverUnknownBatch();

    /**
     * 有界检查需要人工关注的任务，记录去重后的提醒状态并发出运维提示。
     * 返回本批提醒数量；默认实现不提醒并返回零。
     */
    default Mono<Integer> alertReviews() {
        return Mono.just(0);
    }

    /**
     * 在调度容量和核对限额内，为指定 Worker 领取到期任务，同时取得任务与原 Attempt 的核对租约。
     * 返回后续查询和提交所需的固定快照及版本凭据；不创建新的工具执行 Attempt。
     *
     * @param limit 本次领取数量上限，实际数量还受可用容量等条件约束
     */
    Mono<List<ToolOutcomeCheck.Claim>> claim(String workerId, int limit);

    /**
     * 重新校验领取凭据、租约和原操作身份，在同一事务中保存证据及结果，或安排后续核对／转人工。
     *
     * @param reason      记录核对进度的原因码，不包含业务原文或敏感数据
     * @param needsReview 是否直接转人工处理；此时保留原调用的 UNKNOWN 状态
     * @return 本次核对处理是否已保存，不表示原工具业务执行成功
     */
    Mono<Boolean> finish(ToolOutcomeCheck.Claim claim, ToolOutcomeEvidence evidence, String reason, boolean needsReview);

    /**
     * 按原调用所属租户、工作空间、主体及调用标识读取核对任务；不存在或归属不匹配时返回空。
     * 此归属检查不替代用户接口所需的当前访问授权。
     */
    Mono<ToolOutcomeCheck.Case> find(ExecutionOwner owner, String invocationId);

    /**
     * 在当前事务内校验原调用仍为 UNKNOWN，且任务版本、原 Attempt、租约、容量许可和查询时限均有效。
     * 凭据失效时拒绝查询；本方法不创建事务，调用方必须已处于同一物理事务中。
     */
    void requireCurrentQueryInCurrentTransaction(ToolOutcomeCheck.Claim claim);

    /**
     * 在短事务中执行同步证据读取，并在读取前后校验当前查询资格，防止过期 Worker 使用失效凭据。
     *
     * @param read 复用当前物理事务的只读证据查询，不得重新执行或补发业务操作
     */
    Mono<ToolOutcomeEvidence> query(ToolOutcomeCheck.Claim claim, java.util.function.Supplier<ToolOutcomeEvidence> read);
}
