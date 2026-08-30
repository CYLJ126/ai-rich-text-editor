package com.nip.core.exception;

import com.nip.core.enums.ResultCodeEnum;

import java.io.Serial;

/**
 * 加解密、签名验签异常
 *
 * @author zhangsc
 * @since 2024/7/11 11:26
 */
public class SecretException extends CommonException {
    @Serial
    private static final long serialVersionUID = 6084554940458579471L;

    public SecretException(ResultCodeEnum resultCode) {
        super(resultCode);
    }

    public SecretException(String str) {
        super(str);
    }

    public SecretException(Throwable ex) {
        super(ex);
    }

    public SecretException(ResultCodeEnum resultCode, Throwable ex) {
        super(resultCode, ex);
    }

    public SecretException(ResultCodeEnum resultCode, String str) {
        super(resultCode, str);
    }

    public SecretException(String str, Throwable ex) {
        super(str, ex);
    }

    public SecretException(ResultCodeEnum resultCode, String str, Throwable ex) {
        super(resultCode, str, ex);
    }
}
