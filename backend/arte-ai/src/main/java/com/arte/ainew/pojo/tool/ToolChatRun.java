package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.ResultRef;
import com.arte.core.pojo.platform.ExecutionOwner;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Run 的权威检查点
 * <p>
 * 不使用 Invocation ID 冒充 Run，也不复制子调用的权威执行状态。
 *
 * @param runId      工具聊天根执行的稳定 ID，用于关联固定输入、步骤及耐久工作项。
 * @param owner      Run 所属的租户、工作空间及主体；归属信息不代替当前访问授权。
 * @param state      Run 的整体生命周期状态，表示运行、等待、待核对或终态，须与检查点阶段一致。
 * @param version    检查点的乐观锁版本，每次成功推进递增，用于拒绝并发覆盖；领取和续租不增加此版本。
 * @param checkpoint 当前轮次、推进阶段及关联子执行，用于恢复后判断下一步动作。
 * @param modelSteps 累计已固定的模型步骤计划数，不是模型实际调用次数、Attempt 次数或 Token 用量。
 * @param toolSteps  累计已固定的工具步骤计划数，不表示工具均已受理、执行或成功。
 * @param result     最终完整回答的结果引用，仅 SUCCEEDED 时存在；中间过程和部分输出由子执行保存。
 * @param error      失败、超时或待核对的安全错误事实；待核对须保留 UNKNOWN 确定性，不保存原始异常或敏感正文。
 * @param acceptedAt 首次可靠受理时间，由存储使用数据库时间记录，重放和恢复不重新计时。
 * @param updatedAt  最近一次成功推进的时间，不早于受理时间；不表示子执行最后一次活动时间。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 16:20 ✾
 */
public record ToolChatRun(String runId, ExecutionOwner owner, State state, long version, Checkpoint checkpoint,
                          int modelSteps, int toolSteps, ResultRef result, ExecutionError error, Instant acceptedAt,
                          Instant updatedAt) {

    public enum State {
        ACCEPTED, RUNNING, WAITING_TOOL, WAITING_APPROVAL, PENDING_RECONCILIATION, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT;

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
        }
    }

    public enum Phase {READY_MODEL, WAITING_MODEL, WAITING_TOOLS, WAITING_APPROVAL, RECONCILING, FINISHED}

    /**
     * Run 恢复时使用的推进位置，与步骤映射一起保存，不复制子执行的实时状态。
     *
     * @param roundIndex 当前模型—工具轮次，从 0 开始；同一轮模型及其工具提议共用编号，准备下一轮时递增。
     * @param phase      当前轮内的推进阶段，用于决定准备生成、等待模型／工具／审批／核对或结束收尾。
     * @param children   当前阶段关联的稳定子执行 ID；等待模型时一个，等待工具时可有多个，存储推进至准备或结束阶段时清空。
     *                   列表不是全部历史，也不证明子 Invocation 已受理或执行完成。
     */
    public record Checkpoint(int roundIndex, Phase phase, List<String> children) {

        public Checkpoint {
            ContractChecks.range(roundIndex, "roundIndex", 0, 31);
            Objects.requireNonNull(phase);
            children = ContractChecks.list(children, "checkpoint children", 0, 100);
            children.forEach(id -> ContractChecks.id(id, "child ID"));
            ContractChecks.unique(children, "checkpoint children");
            ContractChecks.require(phase != Phase.READY_MODEL || children.isEmpty(), "Ready checkpoint cannot retain children");
            ContractChecks.require(phase != Phase.WAITING_MODEL || children.size() == 1, "Model checkpoint requires one child");
            ContractChecks.require(phase != Phase.WAITING_TOOLS && phase != Phase.WAITING_APPROVAL && phase != Phase.RECONCILING || !children.isEmpty(), "Waiting checkpoint requires children");
        }
    }

    public ToolChatRun {
        ContractChecks.id(runId, "runId");
        Objects.requireNonNull(owner);
        Objects.requireNonNull(state);
        Objects.requireNonNull(checkpoint);
        ContractChecks.range(version, "version", 0, Long.MAX_VALUE);
        ContractChecks.range(modelSteps, "modelSteps", 0, 32);
        ContractChecks.range(toolSteps, "toolSteps", 0, 100);
        ContractChecks.ordered(acceptedAt, updatedAt, "Run updatedAt");
        ContractChecks.require(switch (checkpoint.phase()) {
            case READY_MODEL -> state == State.ACCEPTED || state == State.RUNNING;
            case WAITING_MODEL -> state == State.RUNNING;
            case WAITING_TOOLS -> state == State.WAITING_TOOL;
            case WAITING_APPROVAL -> state == State.WAITING_APPROVAL;
            case RECONCILING -> state == State.PENDING_RECONCILIATION;
            case FINISHED -> state.terminal();
        }, "Run state and checkpoint disagree");
        ContractChecks.require(state != State.SUCCEEDED || result != null && !result.partial() && error == null, "Successful Run requires complete result");
        ContractChecks.require(state == State.SUCCEEDED || result == null, "Only successful Run has final result");
        ContractChecks.require(state != State.FAILED && state != State.TIMED_OUT || error != null, "Failed Run requires error facts");
        ContractChecks.require(state != State.PENDING_RECONCILIATION || error != null && error.certainty() == ExecutionError.Certainty.UNKNOWN,
                "Reconciliation requires unknown outcome facts");
        ContractChecks.require(error == null || (state == State.PENDING_RECONCILIATION) == (error.certainty() == ExecutionError.Certainty.UNKNOWN), "Unknown outcome must stay in reconciliation");
        ContractChecks.require(state.terminal() || state == State.PENDING_RECONCILIATION || error == null, "Active Run cannot have final error");
    }
}
