package com.arte.core.application.platform;

import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.spi.platform.IdentityProvider;

import java.util.Objects;

/**
 * 按稳定主体读取当前身份；受信快照不能替代停用、注销及类型重检。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
public final class CurrentPrincipalResolver {

    private final IdentityProvider identities;

    public CurrentPrincipalResolver(IdentityProvider identities) {
        this.identities = Objects.requireNonNull(identities);
    }

    public Principal requireActive(TrustedActor actor) {
        Objects.requireNonNull(actor);
        Principal original = actor.principal();
        Principal current;
        try {
            current = identities.findBySubjectId(original.subjectId()).orElseThrow(() -> error(PlatformErrorCodeEnum.UNAUTHENTICATED));
        } catch (PlatformException e) {
            throw e;
        } catch (RuntimeException e) {
            var failure = error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            failure.initCause(e);
            throw failure;
        }
        if (!current.subjectId().equals(original.subjectId()) || current.kind() != original.kind()) {
            throw error(PlatformErrorCodeEnum.IDENTITY_CONFLICT);
        }
        if (original.status() != PrincipalStatusEnum.ACTIVE || current.status() != PrincipalStatusEnum.ACTIVE) {
            throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
        }
        return current;
    }

    public Principal requireUser(TrustedActor actor) {
        Principal current = requireActive(actor);
        if (current.kind() != PrincipalKindEnum.USER) throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
        return current;
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "identity-check");
    }
}
