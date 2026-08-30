package com.arte.app.web.advice;

import com.arte.core.exception.CommonException;
import com.arte.core.pojo.ResultContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：把未被 Controller 捕获的异常统一转换为 ResultContext 返回。
 *
 * <p>消息文案经 {@link com.arte.core.utils.ExceptionUtil#desensitize} 脱敏，
 * 并按当前请求语言（Accept-Language）翻译后返回前端。
 *
 * @author haiqingd
 * @since 2026/8/30
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：预期内的业务分支失败，warn 级别记录
     */
    @ExceptionHandler(CommonException.class)
    public ResultContext<Void> handleCommonException(CommonException e) {
        log.warn("业务异常: {}", e.getMessage(), e);
        return ResultContext.exception(e);
    }

    /**
     * 兜底异常：未预期的系统错误，error 级别记录
     */
    @ExceptionHandler(Exception.class)
    public ResultContext<Void> handleException(Exception e) {
        log.error("系统异常: {}", e.getMessage(), e);
        return ResultContext.exception(e);
    }
}
