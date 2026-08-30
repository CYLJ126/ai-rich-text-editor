package com.arte.ai.tool;

import cn.hutool.core.util.ArrayUtil;
import org.slf4j.Logger;
import org.slf4j.event.Level;

/**
 * 日志工具类
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/6/7 20:54 ✾
 */
public class LogUtil {

    public static void info(Logger log, String prefix, Object arg, String... delimiter) {
        print(log, Level.INFO, prefix, arg, delimiter);
    }

    public static void warn(Logger log, String prefix, Object arg, String... delimiter) {
        print(log, Level.WARN, prefix, arg, delimiter);
    }

    public static void debug(Logger log, String prefix, Object arg, String... delimiter) {
        print(log, Level.DEBUG, prefix, arg, delimiter);
    }

    public static void trace(Logger log, String prefix, Object arg, String... delimiter) {
        print(log, Level.TRACE, prefix, arg, delimiter);
    }

    private static void print(Logger log, Level level, String prefix, Object arg, String... delimiters) {
        String delimiter = ArrayUtil.isNotEmpty(delimiters) ? delimiters[0] : "----------------------";
        // 根据日志级别，打印日志
        String format = "{}\n{}\n{}\n{}\n";
        switch (level) {
            case WARN:
                log.warn(format, delimiter, prefix, arg, delimiter);
                break;
            case DEBUG:
                log.debug(format, delimiter, prefix, arg, delimiter);
                break;
            case TRACE:
                log.trace(format, delimiter, prefix, arg, delimiter);
                break;
            default:
                log.info(format, delimiter, prefix, arg, delimiter);
                break;
        }
    }
}
