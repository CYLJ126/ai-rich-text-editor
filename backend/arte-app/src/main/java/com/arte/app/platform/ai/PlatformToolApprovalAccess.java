package com.arte.app.platform.ai;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.config.NewAiToolApprovalProperties;
import com.arte.ainew.pojo.tool.ToolApproval;
import com.arte.ainew.pojo.tool.ToolExecutionPolicy;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.ainew.spi.auth.ToolApprovalAccess;
import com.arte.app.platform.ai.tool.LocalTextTools;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.spi.platform.PublicTransactionRunner;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * 复用公共治理锁、当前稳定身份与既有工具资格；审批名单不授予角色，跨主体审批不替换执行身份。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 09:05 ✾
 */
public final class PlatformToolApprovalAccess implements ToolApprovalAccess {

    private final PlatformToolManagementAccess platform;
    private final RbacIdentityProvider identities;
    private final WorkspaceGovernanceLocks locks;
    private final PublicTransactionRunner transactions;
    private final LocalTextTools tools;
    private final NewAiToolApprovalProperties properties;
    private final Scheduler scheduler;
    private final Clock clock;

    public PlatformToolApprovalAccess(PlatformToolManagementAccess platform, RbacIdentityProvider identities,
                                      WorkspaceGovernanceLocks locks, PublicTransactionRunner transactions, LocalTextTools tools,
                                      NewAiToolApprovalProperties properties, Scheduler scheduler, Clock clock) {
        this.platform = platform;
        this.identities = identities;
        this.locks = locks;
        this.transactions = transactions;
        this.tools = tools;
        this.properties = properties;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    private <T> Mono<T> io(ExecutionContext context, Supplier<T> work) {
        return Mono.defer(() -> Mono.fromCallable(() -> transactions.required(work)).subscribeOn(scheduler)
                        .timeout(java.time.Duration.between(clock.instant(), context.deadline())))
                .onErrorMap(com.arte.core.exception.platform.PlatformException.class, error -> switch (error.getCode()) {
                    case UNAUTHENTICATED, ACCESS_DENIED, POLICY_DENIED, NOT_FOUND, SPACE_READ_ONLY, SPACE_CLOSING,
                         CAPABILITY_NOT_ENABLED, IDENTITY_CONFLICT, TENANT_INACTIVE -> denied();
                    default -> error;
                });
    }

    @Override
    public Mono<PlanPermit> authorizePlan(ToolExecutionSnapshot snapshot, ToolApproval.Plan plan, ExecutionContext requester) {
        return io(requester, () -> {
            checkPlan(snapshot, plan, requester);
            return (actual, fixed) -> checkPlan(actual, fixed, requester);
        });
    }

    private void checkPlan(ToolExecutionSnapshot snapshot, ToolApproval.Plan plan, ExecutionContext current) {
        transactions.requireWritable();
        if (!properties.enabled() || !ExecutionOwner.from(current).equals(snapshot.binding().owner())) throw denied();
        plan.requireMatches(snapshot);
        tools.requireFree(snapshot.input(), snapshot.binding());
        if (plan.mode() == ToolExecutionPolicy.ApprovalMode.INDEPENDENT_APPROVAL && !configured(snapshot).containsAll(plan.approverSubjectIds()))
            throw denied();
        // 必须先按全局顺序锁全部主体，之后才进入任何租户／空间门控；不能在 requester 已持父锁后追加审批人主体锁。
        var subjects = new TreeSet<>(plan.approverSubjectIds());
        subjects.add(snapshot.binding().owner().subjectId());
        subjects.forEach(locks::subject);
        platform.requireUseInCurrentTransaction(current, snapshot.binding().tool().tool().risk().requiredScopes());
        for (var subject : plan.approverSubjectIds()) {
            identities.findCurrentBySubjectId(subject).filter(p -> p.status() == PrincipalStatusEnum.ACTIVE)
                    .orElseThrow(PlatformToolApprovalAccess::denied);
            var name = identities.currentSubjectName(subject).orElseThrow(PlatformToolApprovalAccess::denied);
            var actor = new ExecutionContext(current.executionId(), current.traceId(), new ExecutionAuthorization(
                    new ExecutionPrincipal(subject, name, ExecutionPrincipal.Kind.USER), snapshot.binding().owner().tenantId(),
                    snapshot.binding().owner().workspaceId(), Set.of(AdmissionAuthorization.READ), "tool-approval-policy"),
                    current.deadline(), null, null, current.releaseRef(), current.idempotencyKey());
            platform.requireApprovalReadInCurrentTransaction(actor, true);
        }
    }

    @Override
    public Mono<Permit> authorize(ToolApproval approval, ToolExecutionSnapshot snapshot, ExecutionContext actor, boolean deciding) {
        return io(actor, () -> {
            check(approval, snapshot, actor, deciding);
            return (Permit) (actual, fixed) -> check(actual, fixed, actor, deciding);
        });
    }

    private void check(ToolApproval approval, ToolExecutionSnapshot snapshot, ExecutionContext actor, boolean deciding) {
        transactions.requireWritable();
        if (!actor.authorization().tenantId().equals(approval.owner().tenantId()) || !actor.authorization().workspaceId().equals(approval.owner().workspaceId()))
            throw denied();
        tools.requireFree(snapshot.input(), snapshot.binding());
        String subject = actor.authorization().principal().subjectId();
        locks.subject(subject);
        boolean requester = subject.equals(approval.owner().subjectId());
        if (requester) {
            if (deciding && approval.plan().mode() != ToolExecutionPolicy.ApprovalMode.REQUESTER_CONFIRMATION)
                throw denied();
            if (deciding)
                platform.requireConfirmationInCurrentTransaction(actor, snapshot.binding().tool().tool().risk().requiredScopes());
            else platform.requireApprovalReadInCurrentTransaction(actor, false);
        } else {
            if (approval.plan().mode() != ToolExecutionPolicy.ApprovalMode.INDEPENDENT_APPROVAL
                    || !approval.plan().approverSubjectIds().contains(subject) || !configured(snapshot).contains(subject))
                throw denied();
            platform.requireApprovalReadInCurrentTransaction(actor, true);
        }
    }

    private Set<String> configured(ToolExecutionSnapshot snapshot) {
        return properties.approvers().stream().filter(p -> p.tenantId().equals(snapshot.binding().owner().tenantId())
                        && p.workspaceId().equals(snapshot.binding().owner().workspaceId())).findFirst()
                .map(NewAiToolApprovalProperties.Approvers::subjectIds).orElse(Set.of());
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Current tool approval qualification denied");
    }
}
