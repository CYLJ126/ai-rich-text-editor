package com.arte.ainew.common.execution;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.util.Objects;

/**
 * 长期对象的归属，不复制会过期的授权快照；由可信主体解析，不能相信外部请求体自报值。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record ExecutionOwner(String tenantId, String workspaceId, String subjectId) implements Serializable {
    public ExecutionOwner {
        ContractChecks.id(tenantId, "tenantId");
        ContractChecks.id(workspaceId, "workspaceId");
        ContractChecks.id(subjectId, "subjectId");
    }

    public static ExecutionOwner from(ExecutionContext context) {
        var authorization = Objects.requireNonNull(context, "context").authorization();
        return new ExecutionOwner(authorization.tenantId(), authorization.workspaceId(),
                authorization.principal().subjectId());
    }
}
