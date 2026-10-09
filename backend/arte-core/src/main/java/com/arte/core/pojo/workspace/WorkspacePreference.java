package com.arte.core.pojo.workspace;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;
import java.util.Set;

/**
 * 用户偏好，不授权、不保存当前标签上下文。defaultScope 是初始化关联，普通偏好命令不能修改。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record WorkspacePreference(
        String subjectId,
        Scope defaultScope,
        Scope startupScope,
        Scope lastScope,
        Set<Scope> favorites,
        long version) {
    public WorkspacePreference {
        PlatformChecks.id(subjectId, "subjectId");
        Objects.requireNonNull(defaultScope, "defaultScope");
        favorites = Set.copyOf(favorites);
        PlatformChecks.version(version, "version");
    }
}
