package com.nip.core.exception;

import com.nip.core.enums.ResultCodeEnum;

import java.io.Serial;

/**
 * 业务异常
 *
 * @author zhangsc
 * @since 2024/7/11 11:28
 */
public class BusinessException extends CommonException {

    @Serial
    private static final long serialVersionUID = 6609249756567683685L;

    public BusinessException(ResultCodeEnum resultCode) {
        super(resultCode);
    }

    public BusinessException(String str) {
        super(str);
    }

    public BusinessException(Throwable ex) {
        super(ex);
    }

    public BusinessException(ResultCodeEnum resultCode, Throwable ex) {
        super(resultCode, ex);
    }

    public BusinessException(ResultCodeEnum resultCode, String str) {
        super(resultCode, str);
    }

    public BusinessException(String str, Throwable ex) {
        super(str, ex);
    }

    public BusinessException(ResultCodeEnum resultCode, String str, Throwable ex) {
        super(resultCode, str, ex);
    }
}
