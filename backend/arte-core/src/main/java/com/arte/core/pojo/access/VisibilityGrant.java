package com.arte.core.pojo.access;

import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 切换 SPACE_SHARED 时的明确角色／空间动作，不能隐含完全控制。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record VisibilityGrant(
        GrantRecipient recipient,
        Set<Action> actions,
        Instant expiresAt) {
    public VisibilityGrant {
        Objects.requireNonNull(recipient, "recipient");
        actions = Set.copyOf(actions);
        PlatformChecks.require(!(recipient instanceof GrantRecipient.Subject), "visibility grant requires role or space");
        PlatformChecks.require(!actions.isEmpty() && actions.size() <= 100, "invalid action count");
    }
}
