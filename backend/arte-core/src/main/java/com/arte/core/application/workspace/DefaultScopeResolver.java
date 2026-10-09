package com.arte.core.application.workspace;

import com.arte.core.api.workspace.ScopeResolver;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.VerifiedScope;

import java.util.Objects;

/**
 * 当前身份、租户／空间成员、状态及政策的统一范围验证。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
public final class DefaultScopeResolver implements ScopeResolver {

    private final WorkspaceAccessSupport access;

    public DefaultScopeResolver(WorkspaceAccessSupport access) {
        this.access = Objects.requireNonNull(access);
    }

    @Override
    public VerifiedScope verify(TrustedActor actor, Scope scope) {
        return access.snapshot(actor, scope).verified();
    }
}
