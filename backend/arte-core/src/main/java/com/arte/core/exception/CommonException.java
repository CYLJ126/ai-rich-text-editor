package com.arte.core.exception;

import cn.hutool.core.text.CharSequenceUtil;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.i18n.MessageUtils;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serial;

/**
 * 基础（通用）异常
 *
 * @deprecated 新代码使用 com.arte.base.exception.BaseException 和
 * com.arte.base.model.execution.ExecutionError 表达稳定错误码、失败阶段及结果确定性。
 * 本类保留旧构造方法、结果码和国际化行为；新旧错误不自动映射，既有子类与调用方暂不迁移。
 *
 * @author zhangsc
 * @since 2024/7/11 11:24
 */
@Getter
@NoArgsConstructor
@Deprecated
public class CommonException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 340131292565367615L;

    private ResultCodeEnum resultCode;

    public CommonException(ResultCodeEnum resultCode) {
        super(resultCode.getDesc());
        this.resultCode = resultCode;
    }

    public CommonException(String str) {
        super(MessageUtils.get(str));
        this.resultCode = ResultCodeEnum.EXCEPTION;
    }

    public CommonException(Throwable ex) {
        super(ex);
        this.resultCode = ResultCodeEnum.EXCEPTION;
    }

    public CommonException(ResultCodeEnum resultCode, String str) {
        super(MessageUtils.get(CharSequenceUtil.blankToDefault(str, resultCode.getDesc())));
        this.resultCode = resultCode;
    }


    public CommonException(ResultCodeEnum resultCode, Throwable ex) {
        super(resultCode.getDesc(), ex);
        this.resultCode = resultCode;
    }

    public CommonException(String str, Throwable ex) {
        super(MessageUtils.get(str), ex);
        this.resultCode = ResultCodeEnum.EXCEPTION;
    }

    public CommonException(ResultCodeEnum resultCode, String str, Throwable ex) {
        super(MessageUtils.get(CharSequenceUtil.blankToDefault(str, resultCode.getDesc())), ex);
        this.resultCode = resultCode;
    }
}
