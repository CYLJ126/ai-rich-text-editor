package com.arte.base.model.execution;

import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.validation.ContractChecks;

/**
 * 幂等键、操作和请求摘要均必填。摘要由所属操作基于规范化输入生成，base 不规定算法。
 * key 不是全局标识，去重时使用 identityIn(scope)，并比较 requestDigest。
 * 生成幂等与正式业务应用的 applicationKey 分开管理。
 */
public record IdempotencyKey(String key, String operation, String requestDigest) {

    public IdempotencyKey {
        key = ContractChecks.identifier(key, "key");
        operation = ContractChecks.identifier(operation, "operation");
        requestDigest = ContractChecks.identifier(requestDigest, "requestDigest");
    }

    public IdempotencyIdentity identityIn(ExecutionScope scope) {
        return new IdempotencyIdentity(scope, operation, key);
    }
}
