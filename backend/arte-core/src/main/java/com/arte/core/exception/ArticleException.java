package com.arte.core.exception;

import com.arte.core.enums.ResultCodeEnum;

import java.io.Serial;

/**
 * 文章处理异常类
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/29 14:30 ✾
 **/
public class ArticleException extends CommonException {

    @Serial
    private static final long serialVersionUID = -1803193829642950595L;

    public ArticleException(ResultCodeEnum resultCode) {
        super(resultCode);
    }

    public ArticleException(String str) {
        super(str);
    }

    public ArticleException(Throwable ex) {
        super(ex);
    }

    public ArticleException(ResultCodeEnum resultCode, Throwable ex) {
        super(resultCode, ex);
    }

    public ArticleException(ResultCodeEnum resultCode, String str) {
        super(resultCode, str);
    }

    public ArticleException(String str, Throwable ex) {
        super(str, ex);
    }

    public ArticleException(ResultCodeEnum resultCode, String str, Throwable ex) {
        super(resultCode, str, ex);
    }
}
