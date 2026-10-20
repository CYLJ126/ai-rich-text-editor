package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.ResultRef;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 内部短事务命令；租约不是用户授权，重放仍须重新检查当前资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 16:20 ✾
 */
public final class ToolChatCommands {

    /**
     * Run 命令表中保留的停止门闩键；普通推进命令不得占用该命名空间。
     */
    public static final String CANCEL_MARKER = com.arte.ainew.serialization.CanonicalJson.key("tool-chat-cancel-marker-v1");

    private ToolChatCommands() {
    }

    /**
     * Worker 领取 Run 工作项后获得的租约凭据，用于续租和推进时校验持有者及 fencing，防止失租后继续写入。
     * signalVersion 记录领取时已看到的唤醒信号，避免后续休眠覆盖新到达的通知。
     */
    public record Lease(String runId, ExecutionOwner owner, String workerId, long fence, long signalVersion,
                        Instant until) {

        public Lease {
            ContractChecks.id(runId, "runId");
            Objects.requireNonNull(owner);
            ContractChecks.id(workerId, "workerId");
            ContractChecks.range(fence, "fence", 1, Long.MAX_VALUE);
            ContractChecks.range(signalVersion, "signalVersion", 0, Long.MAX_VALUE);
            Objects.requireNonNull(until);
        }
    }

    /**
     * 一次检查点推进命令，用于原子保存目标状态、新增步骤和下次唤醒时间；结合租约、版本和命令键防止并发覆盖或重复推进。
     * 只固定步骤计划，不直接调用模型或工具。
     */
    public record Advance(Lease lease, long expectedVersion, String commandKey, ToolChatRun.State state,
                          ToolChatRun.Phase phase, int roundIndex, List<ToolChatStep.Plan> plans,
                          Instant nextWakeAt, ResultRef result, ExecutionError error) {

        public Advance {
            Objects.requireNonNull(lease);
            ContractChecks.range(expectedVersion, "expectedVersion", 0, Long.MAX_VALUE);
            ContractChecks.id(commandKey, "commandKey");
            Objects.requireNonNull(state);
            Objects.requireNonNull(phase);
            ContractChecks.range(roundIndex, "roundIndex", 0, 31);
            plans = ContractChecks.list(plans, "new step plans", 0, 100);
            ContractChecks.require(plans.stream().mapToLong(p -> p.input() instanceof ToolChatStep.Model model
                            ? model.context().messages().stream().mapToLong(com.arte.ainew.pojo.generation.ChatMessage::characterCount).sum()
                            : ((ToolChatStep.Tool) p.input()).call().arguments().characterCount()).sum() <= ContractChecks.MAX_TEXT_CHARS,
                    "Run step batch exceeds character limit");
        }
    }

    /**
     * 已提交推进的回执，保存本次推进后的 Run 和新增步骤，供提交响应及同键重放使用。
     * 回执可能早于当前 Run 版本，继续编排前应重新读取最新检查点。
     */
    public record Receipt(ToolChatRun run, List<ToolChatStep> steps) {

        public Receipt {
            Objects.requireNonNull(run);
            steps = ContractChecks.list(steps, "planned steps", 0, 100);
            ContractChecks.unique(steps.stream().map(ToolChatStep::childExecutionId).toList(), "planned children");
            ContractChecks.require(steps.stream().allMatch(s -> s.runId().equals(run.runId())), "Receipt step owner mismatch");
        }
    }

    /**
     * 事务内授权回调的校验材料，将固定输入与当前 Run 一起交给宿主检查资格；首次推进和命令重放都需要校验。
     */
    public record Guard(ToolChatRunSnapshot snapshot, ToolChatRun run) {
    }
}
