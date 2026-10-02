package com.arte.app.security.bridge;

import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import com.arte.base.model.security.PolicyDecision;
import com.arte.base.model.security.PolicyOutcome;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 每次调用重新创建的策略检查状态，不缓存允许结果。
 */
final class BridgePolicyEvaluation {
    final JdbcSecurityRepository repository;
    final Instant now;
    final Map<String, String> versions = new LinkedHashMap<>();
    private Instant validUntil;

    BridgePolicyEvaluation(JdbcSecurityRepository repository, Instant now) {
        this.repository = repository;
        this.now = now;
        this.validUntil = now.plusSeconds(5);
    }

    JdbcSecurityRepository.Account identity(ExecutionContext context) {
        var account = repository.account(context.scope().principal()).orElseThrow(() -> deny("identity_unknown"));
        if (!account.enabled()) throw deny("identity_disabled");
        version("identity", account.revision() == null ? "unversioned" : account.revision());
        var membership = repository.membership(context).orElseThrow(() -> deny("membership_missing"));
        if (!membership.enabled()) throw deny("membership_revoked");
        version("membership", membership.revision());
        if (context.isExpiredAt(now)) throw deny("task_expired");
        if (context.deadline() != null) limit(context.deadline());
        return account;
    }

    void task(ExecutionContext context, PrincipalRef executor, String action) {
        if (!context.authorizationScopes().contains(action)) throw deny("task_action_missing");
        var task = repository.task(context).orElseThrow(() -> deny("task_unregistered"));
        if (!task.enabled() || !now.isBefore(task.validUntil())) throw deny("task_revoked_or_expired");
        limit(task.validUntil());
        version("task", task.revision());
        taskAction(context, context.scope().principal(), action);
        if (!executor.equals(context.scope().principal())) {
            if (executor.type() != PrincipalType.SERVICE) throw deny("executor_invalid");
            var service = repository.service(executor.principalId()).orElseThrow(() -> deny("service_unknown"));
            if (!service.enabled()) throw deny("service_disabled");
            version("service", service.revision());
            taskAction(context, executor, action);
        }
        var policy = repository.applicationPolicy(context, task, action)
                .orElseThrow(() -> unknown("application_policy_missing"));
        if (!policy.applicationEnabled() || !policy.bindingEnabled()) throw deny("application_or_binding_disabled");
        version("application." + action, policy.revision());
    }

    private void taskAction(ExecutionContext context, PrincipalRef principal, String action) {
        var grant = repository.taskAction(context, principal, action).orElseThrow(() -> deny("executor_action_missing"));
        if (!grant.enabled()) throw deny("executor_action_revoked");
        version("task-action." + principal.type() + "." + principal.principalId() + "." + action, grant.revision());
    }

    void version(String id, String revision) {
        versions.put("arte.security." + id, revision);
    }

    void limit(Instant until) {
        if (until.isBefore(validUntil)) validUntil = until;
    }

    PolicyDecision allow() {
        if (!now.isBefore(validUntil))
            return PolicyDecision.deny(Set.of("arte.security.policy_expired"), versions, now);
        return PolicyDecision.allow(versions, now, validUntil);
    }

    PolicyDecision rejection(Rejection rejection) {
        return rejection.outcome == PolicyOutcome.DENY
                ? PolicyDecision.deny(Set.of(rejection.code), versions, now)
                : PolicyDecision.indeterminate(Set.of(rejection.code), now);
    }

    static Rejection deny(String code) {
        return new Rejection(PolicyOutcome.DENY, code);
    }

    static Rejection unknown(String code) {
        return new Rejection(PolicyOutcome.INDETERMINATE, code);
    }

    static final class Rejection extends RuntimeException {
        final PolicyOutcome outcome;
        final String code;

        Rejection(PolicyOutcome outcome, String code) {
            super("arte.security." + code);
            this.outcome = outcome;
            this.code = "arte.security." + code;
        }
    }
}
