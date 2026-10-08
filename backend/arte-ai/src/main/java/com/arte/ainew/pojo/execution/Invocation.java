package com.arte.ainew.pojo.execution;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.CapabilityDescriptor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 一次逻辑能力调用的权威状态快照
 * <p>
 * 记录一次逻辑调用的状态，例如 ACCEPTED、RUNNING、SUCCEEDED、FAILED、UNKNOWN，以及当前 Attempt、结果引用、错误和版本。
 * 可关联会话中的 Turn，状态以执行存储的耐久记录为准。
 * “权威”的意思是：判断调用是否完成，应以 ExecutionStore 中耐久提交的 Invocation 状态为准。Worker 的本地变量、模型流结束或 HTTP 返回成功，都不能单独证明调用成功。
 * <p>
 * id 为 request.context.executionId；version 用于条件更新。
 * requestDigest 由服务端规范化操作输入计算，排除新生成 ID、trace、授权快照等临时数据。
 * 自动重试增加 Attempt；主动重新生成创建新 Invocation，通过 replacesInvocationId 关联原调用。
 * 与结果／Outbox 的原子提交、引用归属、租约及历史状态转换由存储应用边界执行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record Invocation(InvocationRequest<? extends CapabilityInput> request, String requestDigest,
                         ConversationLink conversation, String contextSnapshotId, String replacesInvocationId,
                         State state, long version, String activeAttemptId, ResultRef result, ExecutionError error,
                         Instant acceptedAt, Instant updatedAt) implements Serializable {

    public enum State {
        ACCEPTED, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED, UNKNOWN;

        public boolean terminal() {
            return switch (this) {
                case ACCEPTED, QUEUED, RUNNING -> false;
                default -> true;
            };
        }

        /**
         * 状态图不替代 expectedVersion、租约和副作用校验；UNKNOWN 仅经远端核对收敛，禁止重开执行。
         */
        public boolean canTransitionTo(State next) {
            Objects.requireNonNull(next, "next");
            if (this == UNKNOWN) {
                return next == SUCCEEDED || next == FAILED || next == CANCELLED;
            }
            if (terminal() || next == this || next == ACCEPTED) {
                return false;
            }
            return switch (this) {
                case ACCEPTED -> next == QUEUED || next == RUNNING || next == FAILED || next == CANCELLED
                        || next == TIMED_OUT || next == INTERRUPTED;
                case QUEUED -> next == RUNNING || next == FAILED || next == CANCELLED || next == TIMED_OUT
                        || next == INTERRUPTED;
                case RUNNING -> next == QUEUED || next.terminal();
                default -> false;
            };
        }
    }

    public Invocation {
        Objects.requireNonNull(request, "request");
        ContractChecks.digest(requestDigest, "requestDigest");
        ContractChecks.optionalId(contextSnapshotId, "contextSnapshotId");
        ContractChecks.require(request.kind() != CapabilityDescriptor.Kind.GENERATION
                || contextSnapshotId != null, "Generation invocation requires actual context snapshot");
        ContractChecks.optionalId(replacesInvocationId, "replacesInvocationId");
        ContractChecks.require(!request.context().executionId().equals(replacesInvocationId), "Invocation cannot replace itself");
        Objects.requireNonNull(state, "state");
        ContractChecks.range(version, "version", 0, Long.MAX_VALUE);
        ContractChecks.optionalId(activeAttemptId, "activeAttemptId");
        ContractChecks.require(state != State.RUNNING && state != State.SUCCEEDED || activeAttemptId != null,
                "Running or succeeded invocation requires attempt identity");
        ContractChecks.require(state != State.SUCCEEDED || result != null && !result.partial() && error == null,
                "Success requires complete result and no error");
        ContractChecks.require(state.terminal() || result == null && error == null, "Nonterminal invocation cannot have final result or error");
        ContractChecks.require(state != State.FAILED && state != State.TIMED_OUT && state != State.INTERRUPTED
                && state != State.UNKNOWN || error != null, "Failure requires error facts");
        ContractChecks.require(state != State.UNKNOWN || error.certainty() == ExecutionError.Certainty.UNKNOWN,
                "Unknown outcome requires unknown certainty");
        ContractChecks.require(error == null || (state == State.UNKNOWN) == (error.certainty() == ExecutionError.Certainty.UNKNOWN),
                "Unknown certainty must be represented as UNKNOWN state");
        ContractChecks.require(state == State.SUCCEEDED || result == null || result.partial(), "Unsuccessful result must be partial");
        ContractChecks.ordered(acceptedAt, updatedAt, "updatedAt");
    }

    /**
     * 用户停止接收生成内容，但远端结果仍未知。释放会话前还须核对存储中的耐久取消标记；费用独立对账。
     */
    public boolean userStoppedGeneration() {
        return request.kind() == CapabilityDescriptor.Kind.GENERATION && state == State.UNKNOWN
                && error != null && "INVOCATION_CANCELLED".equals(error.code());
    }

    /**
     * 固定会话版本参与受理并发校验；动作与后台能力调用可以没有会话关联。
     */
    public record ConversationLink(String conversationId, long conversationVersion,
                                   String turnId) implements Serializable {
        public ConversationLink {
            ContractChecks.id(conversationId, "conversationId");
            ContractChecks.range(conversationVersion, "conversationVersion", 0, Long.MAX_VALUE);
            ContractChecks.id(turnId, "turnId");
        }
    }
}
