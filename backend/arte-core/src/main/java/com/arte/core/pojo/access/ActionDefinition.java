package com.arte.core.pojo.access;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;
import java.util.Set;

/**
 * 受信动作注册定义
 * <p>
 * 普通分享只允许标为可委派且授予者当前拥有的动作。
 *
 * @param action         注册的单个动作标识，例如 article:read；登记本身不授予该动作权限。
 * @param resourceTypes  该动作适用的资源类型集合，不能为空集合或包含空元素，保存为不可变集合。
 * @param shareDelegable 是否允许通过普通分享委派该动作；为 true 时仍须校验授予者当前拥有该动作及相关授权、政策条件，为 false 时不得通过普通分享授予。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ActionDefinition(Action action, Set<ResourceType> resourceTypes, boolean shareDelegable) {

    public ActionDefinition {
        Objects.requireNonNull(action, "action");
        resourceTypes = Set.copyOf(resourceTypes);
        PlatformChecks.require(!resourceTypes.isEmpty(), "registered action needs resource types");
    }
}
