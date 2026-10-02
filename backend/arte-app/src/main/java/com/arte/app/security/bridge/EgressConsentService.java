package com.arte.app.security.bridge;

import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.security.EgressRequest;
import com.arte.base.security.PolicyChecks;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * 供新入口在用户明确确认后调用；生成内容／采纳内容不得自动调用此方法。
 */
public class EgressConsentService {
    private final ExistingIdentityAdapter identity;
    private final JdbcSecurityRepository repository;
    private final ExistingEgressPolicy egress;
    private final Clock clock;

    public EgressConsentService(ExistingIdentityAdapter identity, JdbcSecurityRepository repository,
                                ExistingEgressPolicy egress, Clock clock) {
        this.identity = identity;
        this.repository = repository;
        this.egress = egress;
        this.clock = clock;
    }

    /**
     * prepared 必须来自服务端准备的最终资料／内容／连接；客户端仅提交待确认执行标识。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResourceRef confirm(HttpServletRequest httpRequest, EgressRequest prepared) {
        var account = identity.requireAccount(httpRequest);
        if (!account.principal().equals(prepared.context().scope().principal()) || prepared.consentRef() != null) {
            throw new AccessDeniedException("arte.security.consent_identity_mismatch");
        }
        var evaluation = new BridgePolicyEvaluation(repository, clock.instant());
        try {
            egress.prerequisites(prepared, evaluation);
        } catch (BridgePolicyEvaluation.Rejection failure) {
            throw new AccessDeniedException(failure.code);
        }
        PolicyChecks.requireActive(prepared.context(), clock.instant(), "consent");
        var task = repository.task(prepared.context()).orElseThrow(() -> new AccessDeniedException("arte.security.task_unregistered"));
        if (!task.enabled() || !clock.instant().isBefore(task.validUntil())) {
            throw new AccessDeniedException("arte.security.task_revoked_or_expired");
        }
        var ref = ResourceRef.saved("egress-consent", UUID.randomUUID().toString(), "1");
        repository.saveConsent(ref, SecurityFingerprints.egress(prepared), task.validUntil());
        return ref;
    }
}
