package com.arte.base.model.security;

import com.arte.base.validation.ContractChecks;

/**
 * 绑定到完整资源授权请求的判定快照；其他主体、操作或资源的判定不能替代本次请求。
 */
public record AuthorizationDecision(AuthorizationRequest request, PolicyDecision policy) {

    public AuthorizationDecision {
        request = ContractChecks.required(request, "request");
        policy = ContractChecks.required(policy, "policy");
    }
}
