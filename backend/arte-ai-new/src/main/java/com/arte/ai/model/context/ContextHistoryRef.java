package com.arte.ai.model.context;

import com.arte.ai.validation.ChatContractChecks;
import com.arte.base.validation.ContractChecks;

/**
 * 实际选入的历史提交／执行引用；存在引用不证明成功、归属或访问权限，组装服务逐项核对。
 */
public record ContextHistoryRef(String turnId, long turnVersion, String executionId) {
    public ContextHistoryRef {
        turnId = ChatContractChecks.identifier(turnId, 64, "turnId");
        ChatContractChecks.positive(turnVersion, "turnVersion");
        executionId = ChatContractChecks.executionId(ContractChecks.required(executionId, "executionId"));
    }
}
