package com.arte.app.security.bridge;

import com.arte.base.api.security.EgressPolicy;
import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.security.AuthorizationRequest;
import com.arte.base.model.security.CommonResourceAction;
import com.arte.base.model.security.EgressDecision;
import com.arte.base.model.security.EgressRequest;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 核对当前主体、任务、资料、受控连接和完整请求同意；此类不执行网络发送。
 */
@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
public class ExistingEgressPolicy implements EgressPolicy {
    private final JdbcSecurityRepository repository;
    private final ExistingAuthorizationService authorization;
    private final Clock clock;

    public ExistingEgressPolicy(JdbcSecurityRepository repository, ExistingAuthorizationService authorization, Clock clock) {
        this.repository = repository;
        this.authorization = authorization;
        this.clock = clock;
    }

    @Override
    public EgressDecision requireAllowed(EgressRequest request) {
        return EgressPolicy.super.requireAllowed(request, clock);
    }

    @Override
    public EgressDecision requireAllowed(EgressRequest request, Clock checkClock) {
        return EgressPolicy.super.requireAllowed(request, checkClock);
    }

    @Override
    public EgressDecision evaluate(EgressRequest request) {
        var evaluation = new BridgePolicyEvaluation(repository, clock.instant());
        try {
            prerequisites(request, evaluation);
            var consent = repository.consent(request.consentRef()).orElseThrow(() -> BridgePolicyEvaluation.deny("consent_missing"));
            if (!consent.enabled() || !evaluation.now.isBefore(consent.validUntil()))
                throw BridgePolicyEvaluation.deny("consent_revoked_or_expired");
            if (!consent.requestDigest().equals(SecurityFingerprints.egress(request)))
                throw BridgePolicyEvaluation.deny("consent_scope_mismatch");
            evaluation.version("consent", consent.revision());
            evaluation.limit(consent.validUntil());
            return new EgressDecision(request, evaluation.allow());
        } catch (BridgePolicyEvaluation.Rejection rejected) {
            return new EgressDecision(request, evaluation.rejection(rejected));
        }
    }

    void prerequisites(EgressRequest request, BridgePolicyEvaluation evaluation) {
        evaluation.identity(request.context());
        evaluation.task(request.context(), request.executor(), CommonResourceAction.AI_PROCESS.code());
        evaluation.task(request.context(), request.executor(), CommonResourceAction.EGRESS.code());
        var connection = repository.connection(request.context(), request.destination().connectionRef())
                .orElseThrow(() -> BridgePolicyEvaluation.unknown("connection_unregistered"));
        if (!connection.enabled() || !connection.origin().equals(request.destination().origin().toASCIIString())) {
            throw BridgePolicyEvaluation.deny("connection_disabled_or_mismatched");
        }
        evaluation.version("connection", connection.revision());
        var task = repository.task(request.context()).orElseThrow(() -> BridgePolicyEvaluation.deny("task_unregistered"));
        var rule = repository.egressRule(request.context(), task, request.destination().connectionRef(), request.purpose())
                .orElseThrow(() -> BridgePolicyEvaluation.deny("purpose_or_destination_not_allowed"));
        if (!rule.enabled()) throw BridgePolicyEvaluation.deny("egress_rule_revoked");
        evaluation.version("egress-rule", rule.revision());
        for (var source : request.sources()) {
            for (var action : new CommonResourceAction[]{CommonResourceAction.READ, CommonResourceAction.AI_PROCESS, CommonResourceAction.EGRESS}) {
                try {
                    authorization.check(new AuthorizationRequest(request.context(), request.executor(), source.resource(), action.code()), evaluation);
                } catch (BaseException failure) {
                    if (CommonErrorCode.UNAUTHORIZED.code().equals(failure.error().code()))
                        throw BridgePolicyEvaluation.deny("source_action_denied");
                    throw BridgePolicyEvaluation.unknown("source_policy_unavailable");
                }
            }
        }
    }
}
