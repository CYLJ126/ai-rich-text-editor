package com.arte.base.model.security;

import com.arte.base.validation.ContractChecks;

/**
 * 绑定完整外发请求的判定快照；不能跨来源、内容摘要、用途、连接版本或目的地复用。
 */
public record EgressDecision(EgressRequest request, PolicyDecision policy) {

    public EgressDecision {
        request = ContractChecks.required(request, "request");
        policy = ContractChecks.required(policy, "policy");
    }
}
