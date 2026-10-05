package com.arte.ainew.pojo.execution;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.conversation.Turn;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 仅供可信应用层调用的存储命令；owner 来源于当前授权，不能直接绑定 HTTP 请求体。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:11 ✾
 */
public final class ExecutionCommands {
    private ExecutionCommands() {
    }

    /**
     * 初始 Invocation、可选 Turn 与派发 Outbox 同事务；幂等作用域由 owner + capability.id 固定导出，定义版本属于请求摘要。
     */
    public record Accept(Invocation invocation, Turn turn) {
        public Accept {
            Objects.requireNonNull(invocation, "invocation");
            ContractChecks.require(invocation.state() == Invocation.State.ACCEPTED && invocation.version() == 0
                    && invocation.activeAttemptId() == null, "Acceptance requires pristine invocation");
            var link = invocation.conversation();
            ContractChecks.require((link == null) == (turn == null), "Conversation acceptance requires its turn");
            if (link != null) {
                ContractChecks.require(link.conversationId().equals(turn.conversationId())
                                && link.turnId().equals(turn.turnId()) && turn.invocationIds().contains(id(invocation)),
                        "Turn must reference accepted invocation and conversation");
            }
        }
    }

    public record Version(ExecutionOwner owner, String invocationId, long expectedVersion) {
        public Version {
            Objects.requireNonNull(owner, "owner");
            ContractChecks.id(invocationId, "invocationId");
            ContractChecks.range(expectedVersion, "expectedVersion", 0, Long.MAX_VALUE - 1);
        }
    }

    /**
     * 所有 Worker 写入同时比较两份版本、activeAttempt、Worker、fencing token 和数据库时钟租约。
     */
    public record Guard(Version invocation, String attemptId, long expectedAttemptVersion,
                        String workerId, long fencingToken) {
        public Guard {
            Objects.requireNonNull(invocation, "invocation");
            ContractChecks.id(attemptId, "attemptId");
            ContractChecks.range(expectedAttemptVersion, "expectedAttemptVersion", 0, Long.MAX_VALUE - 1);
            ContractChecks.id(workerId, "workerId");
            ContractChecks.range(fencingToken, "fencingToken", 1, Long.MAX_VALUE);
        }

        public static Guard from(ExecutionOwner owner, Invocation invocation, Attempt attempt) {
            ContractChecks.require(id(invocation).equals(attempt.invocationId()), "Attempt belongs to another invocation");
            return new Guard(new Version(owner, id(invocation), invocation.version()), attempt.attemptId(),
                    attempt.version(), attempt.workerId(), attempt.fencingToken());
        }
    }

    /**
     * attemptNumber、fencingToken 由数据库分配；重复消息不能自行选择新序号启动重试。
     */
    public record CreateAttempt(Version invocation, String attemptId, String workerId, Duration lease) {
        public CreateAttempt {
            Objects.requireNonNull(invocation, "invocation");
            ContractChecks.id(attemptId, "attemptId");
            ContractChecks.id(workerId, "workerId");
            validLease(lease);
        }
    }

    public enum LeasePurpose {EXECUTE, RECONCILE}

    /**
     * EXECUTE 不接管已经可能发送的尝试；RECONCILE 只能核对，不能调用 markDispatch 再发。
     */
    public record AcquireLease(Version invocation, String attemptId, long expectedAttemptVersion,
                               String workerId, Duration lease, LeasePurpose purpose) {
        public AcquireLease {
            Objects.requireNonNull(invocation, "invocation");
            ContractChecks.id(attemptId, "attemptId");
            ContractChecks.id(workerId, "workerId");
            ContractChecks.range(expectedAttemptVersion, "expectedAttemptVersion", 0, Long.MAX_VALUE - 1);
            validLease(lease);
            Objects.requireNonNull(purpose, "purpose");
        }
    }

    /**
     * 必须提交成功后才发外部请求；可记录供应商幂等请求 ID，禁止将超时解释为未发送。
     */
    public record Dispatch(Guard guard, String remoteRequestId) {
        public Dispatch {
            Objects.requireNonNull(guard, "guard");
            ContractChecks.optionalId(remoteRequestId, "remoteRequestId");
        }
    }

    /**
     * 条件更新只保存失败尝试；Invocation 终态必须使用 Complete 原子提交。
     */
    public record FailAttempt(Guard guard, Attempt.State state,
                              com.arte.ainew.common.execution.ExecutionError error, Usage usage) {
        public FailAttempt {
            Objects.requireNonNull(guard, "guard");
            ContractChecks.require(state == Attempt.State.FAILED || state == Attempt.State.TIMED_OUT
                    || state == Attempt.State.INTERRUPTED, "Uncertain/terminal execution requires completion");
            Objects.requireNonNull(error, "error");
            ContractChecks.require(error.certainty() == com.arte.ainew.common.execution.ExecutionError.Certainty.KNOWN,
                    "Unknown attempt must complete as UNKNOWN");
            Objects.requireNonNull(usage, "usage");
        }
    }

    /**
     * terminal、Attempt、ResultRef、terminal event 和发布 Outbox 同事务；对账时 evidenceRef 必填。
     */
    public record Complete(Guard guard, String completionKey, ExecutionPayload.Terminal terminal,
                           Usage usage, String evidenceRef) {
        public Complete {
            Objects.requireNonNull(guard, "guard");
            ContractChecks.id(completionKey, "completionKey");
            Objects.requireNonNull(terminal, "terminal");
            Objects.requireNonNull(usage, "usage");
            ContractChecks.optionalId(evidenceRef, "evidenceRef");
        }
    }

    /**
     * 尚无 Attempt 的受理失败／取消／超时；不能伪造成功或未知外部结果。
     */
    public record CompleteBeforeAttempt(Version invocation, String completionKey, ExecutionPayload.Terminal terminal) {
        public CompleteBeforeAttempt {
            Objects.requireNonNull(invocation, "invocation");
            ContractChecks.id(completionKey, "completionKey");
            Objects.requireNonNull(terminal, "terminal");
            ContractChecks.require(terminal.state() != Invocation.State.SUCCEEDED
                            && terminal.state() != Invocation.State.UNKNOWN && terminal.result() == null,
                    "Undispatched completion cannot have external result");
        }
    }

    /**
     * batchKey 在 executionId 内唯一；同键同内容返回原事件和原序号，同键异内容拒绝。
     */
    public record Append(Guard guard, String batchKey, List<ExecutionPayload.OutputBatch> batches) {
        public Append {
            Objects.requireNonNull(guard, "guard");
            ContractChecks.id(batchKey, "batchKey");
            batches = ContractChecks.list(batches, "batches", 1, 32);
            ContractChecks.require(batches.stream().flatMap(b -> b.events().stream())
                    .mapToLong(com.arte.ainew.pojo.generation.GenerationEvent::characterCount).sum()
                    <= ContractChecks.MAX_TEXT_CHARS, "Append exceeds aggregate character limit");
        }
    }

    public static String id(Invocation invocation) {
        return invocation.request().context().executionId();
    }

    public static void validLease(Duration lease) {
        Objects.requireNonNull(lease, "lease");
        ContractChecks.require(lease.compareTo(Duration.ofSeconds(1)) >= 0
                && lease.compareTo(Duration.ofMinutes(5)) <= 0, "Lease must be within 1 second and 5 minutes");
    }
}
