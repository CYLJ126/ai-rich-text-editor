package com.arte.base.exception;

import com.arte.base.model.execution.ExecutionError;
import com.arte.base.validation.ContractChecks;

import java.io.Serial;

/**
 * 通用执行异常，领域可继承扩展。对外错误使用 error()，异常 cause 仅供内部诊断。
 * 不解析国际化文案，不根据 Throwable 自动推断副作用、重试性或结果确定性。
 */
public class BaseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = -6345583963881015288L;

    private final ExecutionError error;

    public BaseException(ExecutionError error) {
        this(error, null);
    }

    public BaseException(ExecutionError error, Throwable cause) {
        super(ContractChecks.required(error, "error").code(), cause);
        this.error = error;
    }

    public ExecutionError error() {
        return error;
    }
}
