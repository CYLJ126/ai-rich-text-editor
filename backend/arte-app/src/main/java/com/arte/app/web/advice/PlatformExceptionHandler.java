package com.arte.app.web.advice;

import com.arte.app.web.controller.platform.WorkspaceAccessController;
import com.arte.app.web.controller.platform.WorkspaceController;
import com.arte.app.web.platform.PlatformHttpContext;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.IResult;
import com.arte.core.pojo.PageView;
import com.arte.core.pojo.ResultContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;

/**
 * 公共空间接口同步／异步错误处理
 * <p>
 * 保留分页结构，不影响旧 Controller。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 18:42 ✾
 */
@RestControllerAdvice(assignableTypes = {WorkspaceController.class, WorkspaceAccessController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class PlatformExceptionHandler {

    public record ErrorDetails(String traceId, boolean retryableWithSameKey) {
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<IResult> handle(Exception exception, HttpServletRequest request, Locale locale) {
        PlatformErrorCodeEnum error = errorCode(exception);
        HttpStatus status = error == null ? HttpStatus.INTERNAL_SERVER_ERROR : status(error);
        String traceId = exception instanceof PlatformException platform
                ? platform.getTraceId() : PlatformHttpContext.traceId(request);
        String code = error == null ? "PLATFORM_INTERNAL_ERROR" : "PLATFORM_" + error.name();
        String description = error == null ? ResultCodeEnum.EXCEPTION.getDesc(locale) : error.getDescription();
        IResult body;
        if (request.getRequestURI().endsWith("/listAccessibleWorkspaces")) {
            body = PageView.fail(ResultCodeEnum.FAIL, description, locale).setCode(code);
        } else {
            body = ResultContext.<ErrorDetails>fail(ResultCodeEnum.FAIL, description, locale).setCode(code)
                    .setData(new ErrorDetails(traceId, error == PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
        }
        // 不记录认证头、请求正文、完整认证对象、SQL 或底层异常消息。
        log.warn("Platform HTTP rejected, method={}, path={}, status={}, code={}, traceId={}, type={}",
                request.getMethod(), request.getRequestURI(), status.value(), code, traceId, exception.getClass().getName());
        return ResponseEntity.status(status).header("X-Trace-Id", traceId).body(body);
    }

    private static PlatformErrorCodeEnum errorCode(Exception exception) {
        if (exception instanceof PlatformException platform) return platform.getCode();
        if (exception instanceof AuthenticationException) return PlatformErrorCodeEnum.UNAUTHENTICATED;
        if (exception instanceof AccessDeniedException) return PlatformErrorCodeEnum.ACCESS_DENIED;
        if (exception instanceof MethodArgumentNotValidException || exception instanceof HttpMessageNotReadableException
                || exception instanceof ServletRequestBindingException || exception instanceof ConstraintViolationException
                || exception instanceof HandlerMethodValidationException || exception instanceof MethodArgumentTypeMismatchException
                || exception instanceof IllegalArgumentException || exception instanceof ArithmeticException) {
            return PlatformErrorCodeEnum.INVALID_ARGUMENT;
        }
        if (exception instanceof DataAccessException || exception instanceof RejectedExecutionException)
            return PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE;
        return null;
    }

    private static HttpStatus status(PlatformErrorCodeEnum code) {
        return switch (code) {
            case INVALID_ARGUMENT, UNKNOWN_ACTION -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case ACCESS_DENIED, POLICY_DENIED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case VERSION_CONFLICT, MEMBER_ROLE_CONFLICT, OWNER_TRANSFER_REQUIRED, IDEMPOTENCY_CONFLICT,
                 SPACE_READ_ONLY, SPACE_CLOSING, CAPABILITY_NOT_ENABLED, IDENTITY_CONFLICT, TENANT_INACTIVE ->
                    HttpStatus.CONFLICT;
            case AUTHORITY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
