package com.arte.base.model.identity;

import com.arte.base.validation.ContractChecks;

/**
 * 租户、工作空间及主体的显式作用域，无默认租户、默认空间或默认系统主体。
 * 成员关系和租户／空间归属须由服务端验证；构造成功不代表获得授权。
 */
public record ExecutionScope(String tenantId, String workspaceId, PrincipalRef principal) {

    public ExecutionScope {
        tenantId = ContractChecks.identifier(tenantId, "tenantId");
        workspaceId = ContractChecks.identifier(workspaceId, "workspaceId");
        principal = ContractChecks.required(principal, "principal");
    }
}
