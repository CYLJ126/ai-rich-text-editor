package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.Invocation;

import java.util.List;

/**
 * 人工核对的原操作、责任方和审计命令；不携带执行授权或预算变更。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 12:28 ✾
 */
public final class ToolReconciliation {

    private ToolReconciliation() {
    }

    public enum Action {CLAIM, REQUEST_CHECK, CONFIRM, UNRESOLVABLE}

    public enum Source {DOMAIN_RECEIPT, VERIFIED_BUSINESS_RECORD}

    public record Review(String invocationKey, String assignee, long assignedAt, String nextStep, long dueAt,
                         long alertedAt) {
    }

    public record Detail(ToolOutcomeCheck.Case work, Invocation invocation, ToolExecutionSnapshot snapshot,
                         Review review,
                         boolean mayConfirm, boolean mayClaim, boolean argumentsVisible, boolean evidenceSupported,
                         long serverTime, List<Audit> history) {
    }

    public record Page(List<Detail> records, String nextCursor, boolean exhausted) {
    }

    public record Proposal(Source source, ToolOutcomeEvidence.Decision decision, String reference, String digest,
                           ExecutionError.SideEffect sideEffect, com.arte.core.pojo.value.StructuredValue output,
                           String verificationNote) {
        public Proposal {
            java.util.Objects.requireNonNull(source);
            java.util.Objects.requireNonNull(decision);
            ContractChecks.require(decision != ToolOutcomeEvidence.Decision.STILL_UNKNOWN, "Use UNRESOLVABLE for an unknown fact");
            ContractChecks.id(reference, "evidence reference");
            ContractChecks.digest(digest, "evidence digest");
            java.util.Objects.requireNonNull(sideEffect);
            ContractChecks.text(verificationNote, "verification note", 1000);

        }
    }

    public record Command(String invocationId, String attemptId, String operationKey, long expectedVersion,
                          long expectedInvocationVersion,
                          Action action, String reason, String nextStep, Proposal proposal) {
        public Command {
            ContractChecks.id(invocationId, "invocationId");
            ContractChecks.id(attemptId, "attemptId");
            java.util.Objects.requireNonNull(operationKey);
            java.util.Objects.requireNonNull(action);
            ContractChecks.range(expectedVersion, "expectedVersion", 0, Long.MAX_VALUE - 1);
            ContractChecks.range(expectedInvocationVersion, "expectedInvocationVersion", 0, Long.MAX_VALUE - 1);
            ContractChecks.text(reason, "reason", 1000);
            ContractChecks.text(nextStep, "nextStep", 1000);
            ContractChecks.require((action == Action.CONFIRM) == (proposal != null), "Only a confirmation includes evidence");
        }
    }

    public record Audit(String commandKey, String invocationKey, String actorId, String requestDigest, String action,
                        String outcome,
                        long caseVersion, long invocationVersion, String caseState, String invocationState,
                        String attemptId, String operationKey, String source, String decision,
                        String evidenceRef, String evidenceDigest, String note, String nextStep, long recordedAt) {
    }

    public record Receipt(String invocationId, String outcome, String version, String invocationVersion, String state,
                          String invocationState) {
    }
}
