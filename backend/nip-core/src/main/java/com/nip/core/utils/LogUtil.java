package com.nip.core.utils;

import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.nip.core.constant.CoreConstant;
import org.slf4j.MDC;

/**
 * @author zhangsc
 * @since 2025/1/9 14:24
 */
public class LogUtil {
    private LogUtil() {
    }

    public static String logId() {
        return DigestUtil.md5Hex(IdUtil.fastSimpleUUID()).substring(8, 24);
    }

    public static void setIdIfNull() {
        if (CharSequenceUtil.isBlank(MDC.get(CoreConstant.LOG_TRACE_ID))) {
            MDC.put(CoreConstant.LOG_TRACE_ID, logId());
        }
    }
}
