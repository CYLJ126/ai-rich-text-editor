package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.ClosureWorkStateEnum;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.List;
import java.util.Objects;

/**
 * 获授权治理者读取的进度，不向已移除用户开放原领域正文。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ClosureView(
        String operationId,
        Scope scope,
        ClosureWorkStateEnum state,
        List<ClosureBlocker> blockers,
        long version) {
    public ClosureView {
        PlatformChecks.id(operationId, "operationId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(state, "state");
        blockers = List.copyOf(blockers);
        PlatformChecks.version(version, "version");
    }
}
