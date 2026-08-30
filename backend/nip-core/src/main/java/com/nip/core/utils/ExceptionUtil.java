package com.nip.core.utils;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.text.CharSequenceUtil;
import com.nip.core.enums.ResultCodeEnum;
import com.nip.core.exception.BusinessException;
import com.nip.core.exception.CommonException;

import java.sql.SQLException;

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
     * 对返回外部的异常信息作简单脱敏
     *
     * @param ex 异常
     * @return 转换后的提示信息
     */
    public static String desensitize(Throwable ex) {
        String errMsg = CharSequenceUtil.sub(ex.getMessage(), 0, 256);
        if (ex instanceof SQLException || ex.getCause() instanceof SQLException) {
            return "数据库异常-" + errMsg;
        } else if (ex instanceof IllegalArgumentException || ex.getCause() instanceof IllegalArgumentException) {
            return "参数异常-" + errMsg;
        } else if (ex instanceof BusinessException || ex.getCause() instanceof BusinessException) {
            return "业务异常-" + errMsg;
        } else {
            return "程序运行出错，请联系相关人员！\n" + errMsg;
        }
    }

    /**
     * 脱敏后同时返回系统码和脱敏信息
     *
     * @param ex 异常
     * @return Pair<ENSystemCode, String>
     */
    public static Pair<ResultCodeEnum, String> desensitizePair(Throwable ex) {
        ResultCodeEnum resultCode;
        if (ex instanceof CommonException commonException) {
            resultCode = commonException.getResultCode();
        } else if (ex.getCause() instanceof CommonException commonException) {
            resultCode = commonException.getResultCode();
        } else {
            resultCode = ResultCodeEnum.SYSTEM_EXCEPTION;
        }
        return Pair.of(resultCode, desensitize(ex));
    }
}
