package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.core.pojo.platform.ExecutionOwner;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 审批的固定内容与唯一决定；审批者不替换原执行主体，原执行期限不得延长。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 00:05 ✾
 */
public record ToolApproval(String approvalId, String invocationId, ExecutionOwner owner, Plan plan,
                           String subjectDigest, State state, long version, String decidedBy,
                           Instant createdAt, Instant decidedAt) {

    public enum State {PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED}

    /**
     * 仅由可信政策生成，不直接绑定 HTTP；独立审批必须显式指定其他稳定主体。
     */
    public record Plan(ToolExecutionPolicy.ApprovalMode mode, Set<String> approverSubjectIds,
                       Instant expiresAt, Duration executionMargin) {
        public Plan {
            Objects.requireNonNull(mode);
            ContractChecks.require(mode != ToolExecutionPolicy.ApprovalMode.NONE, "Approval mode required");
            approverSubjectIds = ContractChecks.set(approverSubjectIds, "approvers", 256);
            approverSubjectIds.forEach(id -> ContractChecks.id(id, "approver subject"));
            ContractChecks.require((mode == ToolExecutionPolicy.ApprovalMode.INDEPENDENT_APPROVAL) == !approverSubjectIds.isEmpty(),
                    "Only independent approval has explicit approvers");
            Objects.requireNonNull(expiresAt);
            Objects.requireNonNull(executionMargin);
            ContractChecks.require(executionMargin.compareTo(Duration.ofSeconds(30)) >= 0
                    && executionMargin.compareTo(Duration.ofHours(1)) < 0, "Approval needs at least 30 seconds execution margin");
        }

        public void requireMatches(ToolExecutionSnapshot snapshot) {
            ContractChecks.require(mode == snapshot.policy().approvalMode(), "Approval mode must match fixed policy");
            ContractChecks.require(expiresAt.isAfter(snapshot.receivedAt())
                            && !expiresAt.isAfter(snapshot.request().options().deadline().minus(executionMargin)),
                    "Approval window exceeds original execution deadline");
            ContractChecks.require(!approverSubjectIds.contains(snapshot.binding().owner().subjectId()), "Independent self approval is forbidden");
        }
    }

    public ToolApproval {
        ContractChecks.id(approvalId, "approvalId");
        ContractChecks.id(invocationId, "invocationId");
        Objects.requireNonNull(owner);
        Objects.requireNonNull(plan);
        ContractChecks.digest(subjectDigest, "approval subject digest");
        Objects.requireNonNull(state);
        ContractChecks.range(version, "approval version", 0, Long.MAX_VALUE - 1);
        Objects.requireNonNull(createdAt);
        ContractChecks.require((state == State.PENDING) == (decidedAt == null), "Decision time must match approval state");
        ContractChecks.require(state != State.PENDING || version == 0 && decidedBy == null, "Pristine pending approval required");
        ContractChecks.require(state == State.PENDING || version == 1, "Approval has one final decision");
        ContractChecks.require((state == State.APPROVED || state == State.REJECTED) == (decidedBy != null), "Human decision requires actor");
        ContractChecks.optionalId(decidedBy, "approval actor");
        if (decidedAt != null) ContractChecks.ordered(createdAt, decidedAt, "approval decision");
    }

    public static ToolApproval pending(ToolExecutionSnapshot snapshot, Plan plan) {
        plan.requireMatches(snapshot);
        String invocation = snapshot.request().context().executionId();
        return new ToolApproval(CanonicalJson.key("tool-approval", invocation), invocation, snapshot.binding().owner(), plan,
                digest(snapshot, plan), State.PENDING, 0, null, snapshot.receivedAt(), null);
    }

    /**
     * requestDigest 覆盖版本、参数、配置、Schema、目标与权限上限；补充绝对期限、风险及审批政策。
     */
    public static String digest(ToolExecutionSnapshot snapshot, Plan plan) {
        return CanonicalJson.digest(CanonicalJson.fields("format", "tool-approval-subject-v1",
                "invocation", snapshot.request().context().executionId(), "owner", snapshot.binding().owner(),
                "request", snapshot.requestDigest(), "deadline", snapshot.request().options().deadline(),
                "risk", CanonicalJson.fields("sideEffect", snapshot.binding().tool().tool().risk().sideEffect(),
                        "destructive", snapshot.binding().tool().tool().risk().destructive(),
                        "idempotency", snapshot.binding().tool().tool().risk().idempotency(),
                        "requiredScopes", new TreeSet<>(snapshot.binding().tool().tool().risk().requiredScopes()),
                        "origins", snapshot.binding().tool().tool().risk().allowedNetworkOrigins().stream().map(Object::toString).sorted().toList()),
                "mode", plan.mode(),
                "approvers", new TreeSet<>(plan.approverSubjectIds()), "expiresAt", plan.expiresAt(), "margin", plan.executionMargin()));
    }

    public ToolApproval decided(State next, String actor, Instant at) {
        ContractChecks.require(state == State.PENDING && next != State.PENDING, "Only pending approval can be decided");
        return new ToolApproval(approvalId, invocationId, owner, plan, subjectDigest, next, version + 1, actor, createdAt, at);
    }
}
