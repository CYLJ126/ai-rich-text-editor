package com.arte.ainew.pojo.execution;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 耐久工作指针；发布至少一次，消费者以 messageId 去重。序号 0 代表派发，否则代表已提交事件。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:30 ✾
 */
public record OutboxMessage(String messageId, String invocationId, ExecutionOwner owner, Kind kind, long eventSequence,
                            String workerId, long fencingToken, Instant leaseExpiresAt) {
    public enum Kind {DISPATCH, EVENT}

    public OutboxMessage {
        ContractChecks.id(messageId, "messageId");
        ContractChecks.id(invocationId, "invocationId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(owner, "owner");
        ContractChecks.range(eventSequence, "eventSequence", 0, Long.MAX_VALUE);
        ContractChecks.require((kind == Kind.DISPATCH) == (eventSequence == 0), "Invalid outbox reference");
        ContractChecks.id(workerId, "workerId");
        ContractChecks.range(fencingToken, "fencingToken", 1, Long.MAX_VALUE);
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
    }
}
