package com.arte.base.execution;

import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.execution.ResultCertainty;
import com.arte.base.model.execution.SideEffectStatus;

/**
 * 公共执行护栏的错误构造；阶段性检查不能替调用方断言此前业务没有副作用。
 */
public final class ExecutionFailures {
    private ExecutionFailures() {
    }

    public static BaseException beforeStart(CommonErrorCode code, ExecutionContext context, String stage) {
        boolean transientFailure = code == CommonErrorCode.BUSY || code == CommonErrorCode.RATE_LIMITED || code == CommonErrorCode.POLICY_UNAVAILABLE;
        return failure(code, context, stage, transientFailure, SideEffectStatus.NONE, ResultCertainty.CONFIRMED, null);
    }

    public static BaseException afterStart(CommonErrorCode code, ExecutionContext context, String stage, Throwable cause) {
        return failure(code, context, stage, false, SideEffectStatus.UNKNOWN, ResultCertainty.UNKNOWN, cause);
    }

    private static BaseException failure(CommonErrorCode code, ExecutionContext context, String stage, boolean retryable,
                                         SideEffectStatus effects, ResultCertainty certainty, Throwable cause) {
        return new BaseException(ExecutionError.of(code, stage, retryable, effects, certainty, context.traceId()), cause);
    }
}
