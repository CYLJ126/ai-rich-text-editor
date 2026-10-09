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
        return requireActive(actor, false);
    }

    /**
     * 持治理门控后重检身份，不沿用外层事务的旧读快照。
     */
    public Principal requireActiveCurrent(TrustedActor actor) {
        return requireActive(actor, true);
    }

    /**
     * 按受信身份的稳定主体 ID 重新读取身份，校验主体 ID、类型一致且新旧身份均处于启用状态。
     * <p>
     * 普通读取使用 {@link IdentityProvider#findBySubjectId(String)}；当前读取使用
     * {@link IdentityProvider#findCurrentBySubjectId(String)}，调用方须先取得主体治理门控，
     * 并在原事务中读取最新事实。本方法不负责取得门控或开启事务，也不校验租户、空间权限。
     *
     * @param actor       已认证的受信身份
     * @param currentRead 是否使用当前事务中的最新事实读取；{@code false} 表示普通读取
     * @return 本次读取并通过校验的启用身份
     * @throws NullPointerException 受信身份为 {@code null} 时抛出
     * @throws PlatformException    身份不存在时为 {@code UNAUTHENTICATED}；主体 ID 或类型不一致时为
     *                              {@code IDENTITY_CONFLICT}；新旧任一身份未启用时为 {@code ACCESS_DENIED}。
     *                              身份端口抛出的平台异常原样传递，其他运行时异常转换为
     *                              {@code AUTHORITY_UNAVAILABLE} 并保留原始原因
     */
    private Principal requireActive(TrustedActor actor, boolean currentRead) {
        Objects.requireNonNull(actor);
        Principal original = actor.principal();
        Principal current;
        try {
            current = (currentRead ? identities.findCurrentBySubjectId(original.subjectId())
                    : identities.findBySubjectId(original.subjectId())).orElseThrow(() -> error(PlatformErrorCodeEnum.UNAUTHENTICATED));
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
        return requireUser(actor, false);
    }

    public Principal requireUserCurrent(TrustedActor actor) {
        return requireUser(actor, true);
    }

    private Principal requireUser(TrustedActor actor, boolean currentRead) {
        Principal current = requireActive(actor, currentRead);
        if (current.kind() != PrincipalKindEnum.USER) throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
        return current;
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "identity-check");
    }
}
