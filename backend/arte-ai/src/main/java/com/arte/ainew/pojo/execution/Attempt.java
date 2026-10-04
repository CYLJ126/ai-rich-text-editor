package com.arte.ainew.pojo.execution;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Invocation 下的一次尝试交互对象
 * <p>
 * 一次实际尝试及领取归属。发送／执行前先耐久标记 MAY_HAVE_EXECUTED；连接超时不是未执行证明。
 * leaseExpiresAt 不授权过期 Worker 提交；存储必须同时检查 fencingToken 和 version。
 * UNKNOWN／INTERRUPTED 不盲重发，费用未知时保留预算待对账。attemptNumber 从 1 开始。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record Attempt(String attemptId, String invocationId, int attemptNumber, String workerId,
                      long fencingToken, Instant leaseExpiresAt, long version, State state, Dispatch dispatch,
                      String remoteRequestId, String budgetReservationId, Usage usage, ExecutionError error,
                      Instant createdAt, Instant updatedAt) implements Serializable {
    public enum State {CREATED, RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED, UNKNOWN}

    public enum Dispatch {NOT_STARTED, MAY_HAVE_EXECUTED, CONFIRMED}

    public Attempt {
        ContractChecks.id(attemptId, "attemptId");
        ContractChecks.id(invocationId, "invocationId");
        ContractChecks.range(attemptNumber, "attemptNumber", 1, 10);
        ContractChecks.id(workerId, "workerId");
        ContractChecks.range(fencingToken, "fencingToken", 1, Long.MAX_VALUE);
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        ContractChecks.require(leaseExpiresAt.isAfter(Objects.requireNonNull(createdAt, "createdAt")), "Lease must initially be active");
        ContractChecks.range(version, "version", 0, Long.MAX_VALUE);
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(dispatch, "dispatch");
        ContractChecks.optionalId(remoteRequestId, "remoteRequestId");
        ContractChecks.optionalId(budgetReservationId, "budgetReservationId");
        Objects.requireNonNull(usage, "usage");
        ContractChecks.require(state != State.CREATED || dispatch == Dispatch.NOT_STARTED && error == null,
                "Created attempt cannot have execution or error facts");
        ContractChecks.require(state != State.SUCCEEDED || dispatch == Dispatch.CONFIRMED && error == null,
                "Succeeded attempt requires confirmed execution");
        ContractChecks.require(state != State.RUNNING || error == null, "Running attempt cannot have final error");
        ContractChecks.require(state != State.FAILED && state != State.TIMED_OUT && state != State.INTERRUPTED
                && state != State.UNKNOWN || error != null, "Failure requires error facts");
        ContractChecks.require(state != State.UNKNOWN || dispatch != Dispatch.NOT_STARTED
                && error.certainty() == ExecutionError.Certainty.UNKNOWN, "Unknown attempt must possibly have executed");
        ContractChecks.require(error == null || (state == State.UNKNOWN) == (error.certainty() == ExecutionError.Certainty.UNKNOWN),
                "Unknown certainty must be represented as UNKNOWN state");
        ContractChecks.require(dispatch != Dispatch.NOT_STARTED || remoteRequestId == null,
                "Remote request identity requires dispatch facts");
        ContractChecks.ordered(createdAt, updatedAt, "updatedAt");
    }
}
