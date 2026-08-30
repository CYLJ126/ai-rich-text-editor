package com.nip.core.exception;

import com.nip.core.enums.ResultCodeEnum;

import java.io.Serial;

/**
 * 聊天异常类
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/29 13:48 ✾
 **/
public class ChatException extends CommonException {

    @Serial
    private static final long serialVersionUID = -291375853067239052L;

    public ChatException(ResultCodeEnum resultCode) {
        super(resultCode);
    }

    public ChatException(String str) {
        super(ResultCodeEnum.CHAT_EXCEPTION, str);
    }

    public ChatException(Throwable ex) {
        super(ResultCodeEnum.CHAT_EXCEPTION, ex);
    }

    public ChatException(ResultCodeEnum resultCode, Throwable ex) {
        super(resultCode, ex);
    }

    public ChatException(ResultCodeEnum resultCode, String str) {
        super(resultCode, str);
    }

    public ChatException(String str, Throwable ex) {
        super(ResultCodeEnum.CHAT_EXCEPTION, str, ex);
    }

    public ChatException(ResultCodeEnum resultCode, String str, Throwable ex) {
        super(resultCode, str, ex);
    }
}
