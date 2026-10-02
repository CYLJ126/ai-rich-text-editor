package com.arte.base.model.identity;

import com.arte.base.validation.ContractChecks;

/**
 * 主体标识及种类，不包含登录资料或凭据。
 * 构造只验证结构，认证和主体有效性须由服务端接入层保证。
 */
public record PrincipalRef(String principalId, PrincipalType type) {

    public PrincipalRef {
        principalId = ContractChecks.identifier(principalId, "principalId");
        type = ContractChecks.required(type, "type");
    }
}
