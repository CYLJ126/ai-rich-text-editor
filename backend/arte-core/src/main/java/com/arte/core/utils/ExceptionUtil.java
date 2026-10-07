package com.arte.core.utils;

import cn.hutool.core.lang.Pair;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.exception.CommonException;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

/**
 * 异常处理工具
 *
 * @author zhangsc
 * @since 2025/1/2 15:44
 */
public class ExceptionUtil {

    private ExceptionUtil() {
    }

    /**
     * 返回可公开的异常提示，仅使用已登记结果码的国际化文案。
     *
     * <p>异常类型和消息截断都不能保证安全：业务异常也可能包装 SQL、凭据或服务响应。
     * 因此不读取 Throwable.getMessage()，自定义异常文本和底层原因仅供服务端日志使用。
     * 未登记结果码的异常（包括 null）统一使用系统异常提示。
     *
     * @param ex 异常
     * @return 转换后的提示信息
     */
    public static String desensitize(Throwable ex) {
        return desensitizePair(ex).getValue();
    }

    /**
     * 同时返回结果码和安全提示。
     *
     * <p>沿用直接异常或直接 cause 中的 CommonException 结果码；
     * 提示始终由同一个结果码生成，不拼接包装异常或 cause 的原始消息。
     *
     * @param ex 异常
     * @return 结果码及其国际化提示
     */
    public static Pair<ResultCodeEnum, String> desensitizePair(Throwable ex) {
        return desensitizePair(ex, LocaleContextHolder.getLocale());
    }

    /**
     * 按显式请求语言返回安全文案，不依赖异常产生或响应构建线程的语言。
     */
    public static Pair<ResultCodeEnum, String> desensitizePair(Throwable ex, Locale locale) {
        ResultCodeEnum resultCode = ResultCodeEnum.SYSTEM_EXCEPTION;
        if (ex instanceof CommonException commonException) {
            resultCode = commonException.getResultCode();
        } else if (ex != null && ex.getCause() instanceof CommonException commonException) {
            resultCode = commonException.getResultCode();
        }
        if (resultCode == null) {
            resultCode = ResultCodeEnum.SYSTEM_EXCEPTION;
        }
        return Pair.of(resultCode, resultCode.getDesc(locale));
    }
}
