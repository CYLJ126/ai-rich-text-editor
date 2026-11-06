package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.ClosureWorkStateEnum;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.List;
import java.util.Objects;

/**
 * 向获授权的治理者展示空间关闭进度和阻塞项。
 *
 * @param operationId 对应的关闭操作 ID
 * @param scope       目标空间范围，包含租户 ID 和空间 ID
 * @param state       关闭操作当前状态
 * @param blockers    关闭操作当前的阻塞项
 * @param version     关闭操作记录版本
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
