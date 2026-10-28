package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.Attempt;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.core.pojo.platform.ExecutionOwner;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 原操作的核对工作项；租约和额度不授予 invoke／markDispatch 资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 10:48 ✾
 */
public final class ToolOutcomeCheck {

    private ToolOutcomeCheck() {
    }

    public static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10), LEASE = Duration.ofSeconds(30), WINDOW = Duration.ofMinutes(30);
    public static final List<Duration> DELAYS = List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2),
            Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(10));

    public enum State {CHECKING, NEEDS_REVIEW, UNRESOLVABLE, RESOLVED}

    public record Case(String invocationKey, String invocationId, String ownerKey, String attemptId,
                       String operationKey,
                       String spaceKey, String providerKey, State state, int checks, long version, String workerId,
                       long fence,
                       long leaseUntil, long queryDeadline, long nextCheck, long createdAt, long validUntil,
                       String lastCode, String evidenceKey) {
        public Case {
            ContractChecks.digest(invocationKey, "invocationKey");
            ContractChecks.id(invocationId, "invocationId");
            ContractChecks.digest(ownerKey, "ownerKey");
            ContractChecks.id(attemptId, "attemptId");
            ContractChecks.digest(spaceKey, "spaceKey");
            ContractChecks.digest(providerKey, "providerKey");
            Objects.requireNonNull(state);
            Objects.requireNonNull(workerId);
            Objects.requireNonNull(operationKey);
            ContractChecks.range(checks, "checks", 0, DELAYS.size());
            ContractChecks.range(version, "version", 0, Long.MAX_VALUE);
            ContractChecks.range(fence, "fence", 0, Long.MAX_VALUE);
            ContractChecks.require(state != State.CHECKING || !operationKey.isBlank(), "Checking requires original operation identity");
            ContractChecks.require(validUntil == Math.addExact(createdAt, WINDOW.toMillis()), "Outcome window cannot change");
            ContractChecks.text(lastCode, "outcome code", 96);
            Objects.requireNonNull(evidenceKey);
        }

        public Case changed(State state, int checks, String worker, long fence, long lease, long query, long next, String code, String evidence) {
            return new Case(invocationKey, invocationId, ownerKey, attemptId, operationKey, spaceKey, providerKey, state,
                    checks, Math.addExact(version, 1), worker, fence, lease, query, next, createdAt, validUntil, code, evidence);
        }
    }

    public record Claim(Case work, Invocation invocation, Attempt attempt, ToolExecutionSnapshot snapshot) {
        public ExecutionOwner owner() {
            return ExecutionOwners.from(invocation.request().context());
        }

        public String checkId() {
            return CanonicalJson.key("tool-outcome-check", work.invocationId(), Integer.toString(work.checks()));
        }
    }
}
