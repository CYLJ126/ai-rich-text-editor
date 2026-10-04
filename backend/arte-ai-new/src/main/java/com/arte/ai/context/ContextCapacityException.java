package com.arte.ai.context;

import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.execution.ResultCertainty;
import com.arte.base.model.execution.SideEffectStatus;

/** 只暴露计量事实，帮助缩小范围；不在错误中回显资料正文。 */
public final class ContextCapacityException extends BaseException {
    public record Capacity(int usedInputBytes, int inputByteLimit, int estimatedInputTokens,
                           int inputTokenLimit, int outputTokenReserve, int safetyTokenReserve) { }
    private final Capacity capacity;
    public ContextCapacityException(Capacity capacity) {
        super(ExecutionError.of(CommonErrorCode.INVALID_ARGUMENT, "resource-context-capacity", false,
                SideEffectStatus.NONE, ResultCertainty.CONFIRMED, null));
        this.capacity = capacity;
    }
    public Capacity capacity() { return capacity; }
}
