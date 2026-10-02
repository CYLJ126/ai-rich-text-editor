package com.arte.base.model.execution;

import com.arte.base.validation.ContractChecks;

/**
 * 取消信号的权威执行引用；不存储实例内存中的取消状态，不表示取消已完成。
 */
public record CancellationRef(String executionId) {

    public CancellationRef {
        executionId = ContractChecks.identifier(executionId, "executionId");
    }
}
