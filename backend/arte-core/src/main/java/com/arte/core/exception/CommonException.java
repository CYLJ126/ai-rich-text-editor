package com.arte.core.exception;

import cn.hutool.core.text.CharSequenceUtil;
import com.arte.core.enums.ResultCodeEnum;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serial;

/**
 * 基础（通用）异常
 *
 * @author zhangsc
 * @since 2024/7/11 11:24
 */
@Getter
@NoArgsConstructor
public class CommonException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 340131292565367615L;

    private ResultCodeEnum resultCode;

    public CommonException(ResultCodeEnum resultCode) {
        super(resultCode.getDesc());
        this.resultCode = resultCode;
    }

    public CommonException(String str) {
        super(str);
        this.resultCode = ResultCodeEnum.EXCEPTION;
    }

    public CommonException(Throwable ex) {
        super(ex);
    }

    public CommonException(ResultCodeEnum resultCode, String str) {
        super(CharSequenceUtil.blankToDefault(str, resultCode.getDesc()));
        this.resultCode = resultCode;
    }


    public CommonException(ResultCodeEnum resultCode, Throwable ex) {
        super(resultCode.getDesc(), ex);
        this.resultCode = resultCode;
    }

    public CommonException(String str, Throwable ex) {
        super(str, ex);
    }

    public CommonException(ResultCodeEnum resultCode, String str, Throwable ex) {
        super(CharSequenceUtil.blankToDefault(str, resultCode.getDesc()), ex);
        this.resultCode = resultCode;
    }
}
