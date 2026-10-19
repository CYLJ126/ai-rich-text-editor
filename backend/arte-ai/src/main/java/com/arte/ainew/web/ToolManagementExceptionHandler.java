package com.arte.ainew.web;

import com.arte.ainew.application.execution.DispatchSchedulingException;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.web.controller.NewAiToolController;
import com.arte.ainew.web.controller.NewAiToolSubmissionController;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.ResultContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Locale;

/**
 * TOOL 同步参数与 Mono 异步错误统一安全包装；不返回或记录配置、审核原文和 SQL 参数。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
@RestControllerAdvice(assignableTypes = {NewAiToolController.class, NewAiToolSubmissionController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class ToolManagementExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResultContext<Void>> handle(Exception error, HttpServletRequest request, Locale locale) {
        if (error instanceof DispatchSchedulingException scheduling) {
            boolean full = scheduling.reason() == DispatchSchedulingException.Reason.QUEUE_FULL;
            var code = full ? ResultCodeEnum.AI_DISPATCH_QUEUE_FULL : ResultCodeEnum.AI_DISPATCH_UNAVAILABLE;
            var response = ResponseEntity.status(full ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE);
            if (full) response.header("Retry-After", "5");
            return response.body(ResultContext.fail(code, locale));
        }
        HttpStatus status;
        ResultCodeEnum code = ResultCodeEnum.FAIL;
        if (error instanceof AuthenticationException) status = HttpStatus.UNAUTHORIZED;
        else if (error instanceof AccessDeniedException) status = HttpStatus.FORBIDDEN;
        else if (error instanceof PlatformException platform) {
            status = switch (platform.getCode()) {
                case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
                case AUTHORITY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                default -> HttpStatus.FORBIDDEN;
            };
        } else if (error instanceof AdmissionException admission) {
            code = admission.getResultCode();
            status = code == ResultCodeEnum.AI_NOT_FOUND ? HttpStatus.NOT_FOUND : code == ResultCodeEnum.AI_DEADLINE_EXCEEDED ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.CONFLICT;
        } else if (error instanceof MethodArgumentNotValidException || error instanceof HttpMessageNotReadableException
                || error instanceof ServletRequestBindingException || error instanceof IllegalArgumentException) {
            code = ResultCodeEnum.AI_TOOL_INVALID_REQUEST;
            status = HttpStatus.BAD_REQUEST;
        } else if (error instanceof java.util.concurrent.TimeoutException) {
            code = ResultCodeEnum.AI_DEADLINE_EXCEEDED;
            status = HttpStatus.GATEWAY_TIMEOUT;
        } else {
            code = ResultCodeEnum.AI_TOOL_MANAGEMENT_UNAVAILABLE;
            status = HttpStatus.SERVICE_UNAVAILABLE;
        }
        log.warn("AI_TOOL_HTTP rejected method={} path={} status={} code={} type={}", request.getMethod(), request.getRequestURI(), status.value(), code.getCode(), error.getClass().getSimpleName());
        return ResponseEntity.status(status).body(ResultContext.fail(code, locale));
    }
}
