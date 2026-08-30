package com.arte.core.exception;

import com.arte.core.enums.ResultCodeEnum;

import java.io.Serial;

/**
 * 连接、交互异常
 *
 * @author zhangsc
 * @since 2024/7/11 11:28
 */
public class InteractiveException extends CommonException {
    @Serial
    private static final long serialVersionUID = -9129264231287212113L;

    public InteractiveException(ResultCodeEnum resultCode) {
        super(resultCode);
    }

    public InteractiveException(String str) {
        super(ResultCodeEnum.CONNECTION_EXCEPTION, str);
    }

    public InteractiveException(Throwable ex) {
        super(ResultCodeEnum.CONNECTION_EXCEPTION, ex);
    }

    public InteractiveException(ResultCodeEnum resultCode, Throwable ex) {
        super(resultCode, ex);
    }

    public InteractiveException(ResultCodeEnum resultCode, String str) {
        super(resultCode, str);
    }

    public InteractiveException(String str, Throwable ex) {
        super(ResultCodeEnum.CONNECTION_EXCEPTION, str, ex);
    }

    public InteractiveException(ResultCodeEnum resultCode, String str, Throwable ex) {
        super(resultCode, str, ex);
    }
}
