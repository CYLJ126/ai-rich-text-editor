package com.arte.base.model.execution;

import com.arte.base.model.error.ErrorCode;
import com.arte.base.validation.ContractChecks;

/**
 * 稳定错误信封，不包含自由文案、凭据、提示词或底层异常。
 * code、failureStage、副作用和结果确定性必填；correlationId 可为空。
 * retryable 表示可能允许重试，不保证无需核对即可再次执行。
 */
public record ExecutionError(
        String code,
        String failureStage,
        boolean retryable,
        SideEffectStatus sideEffectStatus,
        ResultCertainty resultCertainty,
        String correlationId
) {

    public ExecutionError {
        code = ContractChecks.identifier(code, "code");
        failureStage = ContractChecks.identifier(failureStage, "failureStage");
        sideEffectStatus = ContractChecks.required(sideEffectStatus, "sideEffectStatus");
        resultCertainty = ContractChecks.required(resultCertainty, "resultCertainty");
        correlationId = ContractChecks.optionalIdentifier(correlationId, "correlationId");
    }

    public static ExecutionError of(ErrorCode code, String failureStage, boolean retryable,
                                    SideEffectStatus sideEffectStatus, ResultCertainty resultCertainty,
                                    String correlationId) {
        ContractChecks.required(code, "code");
        return new ExecutionError(code.code(), failureStage, retryable, sideEffectStatus, resultCertainty, correlationId);
    }

    /**
     * 保守判断：只表示已确认无副作用且允许重试。
     * 实际派发仍须重新授权、校验幂等、预算、期限及次数；外部幂等保证由适配器补充。
     */
    public boolean canRetryWithoutReconciliation() {
        return retryable && sideEffectStatus == SideEffectStatus.NONE && resultCertainty == ResultCertainty.CONFIRMED;
    }
}
