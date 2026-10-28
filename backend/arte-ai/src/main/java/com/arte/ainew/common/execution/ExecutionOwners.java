package com.arte.ainew.common.execution;

import com.arte.core.pojo.platform.ExecutionOwner;

import java.util.Objects;

/**
 * AI 执行上下文到公共归属键的转换；不会完成授权或把快照当作当前主体状态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/28 17:51 ✾
 */
public final class ExecutionOwners {

    private ExecutionOwners() {
    }

    public static ExecutionOwner from(ExecutionContext context) {
        var authorization = Objects.requireNonNull(context, "context").authorization();
        return new ExecutionOwner(authorization.tenantId(), authorization.workspaceId(),
                authorization.principal().subjectId());
    }
}
