package com.arte.core.pojo.access;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;
import java.util.Set;

/**
 * 受信动作注册定义。普通分享只允许标为可委派且授予者当前拥有的动作。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ActionDefinition(
        Action action,
        Set<ResourceType> resourceTypes,
        boolean shareDelegable) {
    public ActionDefinition {
        Objects.requireNonNull(action, "action");
        resourceTypes = Set.copyOf(resourceTypes);
        PlatformChecks.require(!resourceTypes.isEmpty(), "registered action needs resource types");
    }
}
