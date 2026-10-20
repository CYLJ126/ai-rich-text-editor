package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.generation.ToolCall;
import com.arte.ainew.serialization.CanonicalJson;

import java.time.Instant;
import java.util.Objects;

/**
 * 已固定的逻辑步骤映射，保存轮次、输入、子执行身份及操作键，供 Run 持久化和恢复时复用。
 * 计划存在不表示子 Invocation 已受理或业务已执行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 16:20 ✾
 */
public record ToolChatStep(String runId, int roundIndex, Input input, String childExecutionId, String operationKey,
                           Instant plannedAt) {

    /**
     * 模型生成与工具调用两类步骤的固定输入，用于区分后续受理和恢复路径。
     */
    public sealed interface Input permits Model, Tool {
    }

    /**
     * 模型生成步骤的输入，固定该轮实际上下文；恢复时复用原消息和上下文选择，不重新拼装替换。
     */
    public record Model(ContextSnapshot context) implements Input {
        public Model {
            Objects.requireNonNull(context);
        }
    }

    /**
     * 工具步骤的输入，固定模型提出的 callId、准确工具版本和完整参数，供校验及后续子调用受理使用。
     * 执行绑定与策略仍从同一 Run 的允许工具快照取得。
     */
    public record Tool(ToolCall call) implements Input {
        public Tool {
            Objects.requireNonNull(call);
        }
    }

    /**
     * 尚未派生子执行身份的步骤计划，用于推进命令提交本轮新增步骤；存储校验通过后转为固定步骤。
     */
    public record Plan(int roundIndex, Input input) {
        public Plan {
            ContractChecks.range(roundIndex, "roundIndex", 0, 31);
            Objects.requireNonNull(input);
        }
    }

    public ToolChatStep {
        ContractChecks.id(runId, "runId");
        Objects.requireNonNull(input);
        Objects.requireNonNull(plannedAt);
        ContractChecks.range(roundIndex, "roundIndex", 0, 31);
        ContractChecks.require(childExecutionId.equals(childId(runId, new Plan(roundIndex, input)))
                && operationKey.equals(CanonicalJson.key("tool-chat-operation-v1", childExecutionId)), "Invalid stable child identity");
    }

    /**
     * 按 Run、轮次、步骤类型及工具 callId 派生稳定子执行 ID，供重复推进和恢复时定位同一逻辑子操作。
     * ID 不覆盖参数内容，参数变化仍须由推进命令和存储的幂等校验拒绝。
     */
    public static String childId(String runId, Plan plan) {
        return CanonicalJson.key("tool-chat-child-v1", runId, Integer.toString(plan.roundIndex()),
                plan.input() instanceof Model ? "MODEL" : "TOOL", plan.input() instanceof Tool tool ? tool.call().callId() : "");
    }

    /**
     * 将计划转换为固定步骤，派生子执行 ID 和独立操作键并记录计划时间，供推进事务统一持久化。
     * 此方法只构造值对象，不创建子 Invocation 或执行工具。
     */
    public static ToolChatStep planned(String runId, Plan plan, Instant at) {
        String id = childId(runId, plan);
        return new ToolChatStep(runId, plan.roundIndex(), plan.input(), id, CanonicalJson.key("tool-chat-operation-v1", id), at);
    }
}
