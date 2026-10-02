package com.arte.base.model.execution;

import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.validation.ContractChecks;

/**
 * 去重查找键，限定租户、空间、主体及操作；故意不包含请求摘要。
 * 找到同一键后必须比较请求摘要，否则不同输入会被错误地当成新的操作。
 */
public record IdempotencyIdentity(ExecutionScope scope, String operation, String key) {

    public IdempotencyIdentity {
        scope = ContractChecks.required(scope, "scope");
        operation = ContractChecks.identifier(operation, "operation");
        key = ContractChecks.identifier(key, "key");
    }
}
