package com.arte.ainew.web;

import com.arte.ainew.application.execution.DispatchSchedulingException;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.web.controller.*;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.IResult;
import com.arte.core.pojo.PageView;
import com.arte.core.pojo.ResultContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
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
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.sql.SQLException;
import java.util.Locale;

/**
 * 同步参数错误和 Mono 异步错误统一包装，分页失败保持 PageView 的响应结构。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 15:38 ✾
 */
@RestControllerAdvice(assignableTypes = {NewAiConversationController.class, NewAiChatController.class,
        NewAiInvocationController.class, NewAiToolInvocationController.class, NewAiConversationSearchController.class, NewAiBudgetController.class, NewAiReconciliationController.class, NewAiConfigurationController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class ConversationExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<IResult> handle(Exception exception, HttpServletRequest request, Locale locale) {
        if (exception instanceof DispatchSchedulingException scheduling) {
            boolean full = scheduling.reason() == DispatchSchedulingException.Reason.QUEUE_FULL;
            var code = full ? ResultCodeEnum.AI_DISPATCH_QUEUE_FULL : ResultCodeEnum.AI_DISPATCH_UNAVAILABLE;
            var response = ResponseEntity.status(full ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE);
            if (full) response.header("Retry-After", "5");
            return response.body(ResultContext.fail(code, locale));
        }
        var status = HttpStatus.INTERNAL_SERVER_ERROR;
        ResultCodeEnum code = null;
        if (exception instanceof com.arte.ainew.common.search.SearchFailure search) {
            status = switch (search.code()) {
                case SEARCH_EXPIRED -> HttpStatus.GONE;
                case SEARCH_LIMIT_REACHED -> HttpStatus.TOO_MANY_REQUESTS;
                case SEARCH_BUSY -> HttpStatus.CONFLICT;
                case INVALID_CURSOR, GROUP_FILTER_NOT_AVAILABLE -> HttpStatus.BAD_REQUEST;
                default -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            code = switch (search.code()) {
                case SEARCH_EXPIRED -> ResultCodeEnum.AI_SEARCH_EXPIRED;
                case SEARCH_LIMIT_REACHED -> ResultCodeEnum.AI_SEARCH_LIMIT_REACHED;
                case SEARCH_BUSY -> ResultCodeEnum.AI_SEARCH_BUSY;
                case INVALID_CURSOR -> ResultCodeEnum.AI_SEARCH_INVALID_CURSOR;
                case GROUP_FILTER_NOT_AVAILABLE -> ResultCodeEnum.AI_SEARCH_GROUP_NOT_AVAILABLE;
                default -> ResultCodeEnum.AI_SEARCH_UNAVAILABLE;
            };
            var body = ResultContext.fail(code, locale);
            return ResponseEntity.status(status).body(body);
        }
        if (exception instanceof java.util.concurrent.TimeoutException
                || exception instanceof AsyncRequestTimeoutException) {
            status = HttpStatus.GATEWAY_TIMEOUT;
            code = ResultCodeEnum.AI_DEADLINE_EXCEEDED;
        } else if (exception instanceof AuthenticationException) {
            status = HttpStatus.UNAUTHORIZED;
            code = ResultCodeEnum.FAIL;
        } else if (exception instanceof AccessDeniedException) {
            status = HttpStatus.FORBIDDEN;
            code = ResultCodeEnum.FAIL;
        } else if (exception instanceof MethodArgumentNotValidException || exception instanceof HttpMessageNotReadableException
                || exception instanceof ServletRequestBindingException || exception instanceof IllegalArgumentException) {
            status = HttpStatus.BAD_REQUEST;
            code = ResultCodeEnum.FAIL;
        } else if (exception instanceof PlatformException platform) {
            code = ResultCodeEnum.FAIL;
            status = platform.getCode() == PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN;
        } else if (exception instanceof AdmissionException admission) {
            code = admission.getResultCode();
            status = switch (code) {
                case AI_CONVERSATION_NOT_FOUND, AI_TURN_NOT_FOUND, AI_NOT_FOUND, AI_BUDGET_NOT_AVAILABLE ->
                        HttpStatus.NOT_FOUND;
                case AI_PIN_LIMIT_REACHED, AI_VERSION_CONFLICT, AI_IDEMPOTENCY_CONFLICT, AI_CONVERSATION_BUSY,
                     AI_RESULT_NOT_AVAILABLE -> HttpStatus.CONFLICT;
                case AI_CURSOR_EXPIRED -> HttpStatus.GONE;
                case AI_DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT;
                default -> HttpStatus.BAD_REQUEST;
            };
        }
        String path = request.getRequestURI();
        boolean paged = path.endsWith("/listConversations") || path.endsWith("/queryTurnsOfConversation");
        IResult body = paged ? (code == null ? PageView.exception(exception, locale) : PageView.fail(code, locale))
                : (code == null ? ResultContext.exception(exception, locale) : ResultContext.fail(code, locale));
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            var root = NestedExceptionUtils.getMostSpecificCause(exception);
            var sql = root instanceof SQLException failure ? failure : null;
            // 异常作为最后一个参数输出完整 cause/堆栈；不额外序列化请求或绑定参数。
            log.error("New AI HTTP failure, method={}, path={}, status={}, context={}, type={}, rootType={}, sqlState={}, vendorCode={}",
                    request.getMethod(), path, status.value(), request.getAttribute(ConversationRequestDiagnostics.ATTRIBUTE),
                    exception.getClass().getName(), root.getClass().getName(), sql == null ? null : sql.getSQLState(),
                    sql == null ? null : sql.getErrorCode(), exception);
        } else {
            // 可预期拒绝保留标识／分页诊断，不写请求正文、异常原文或认证头。
            log.warn("New AI HTTP rejected, method={}, path={}, status={}, code={}, context={}, type={}",
                    request.getMethod(), path, status.value(), code.getCode(), request.getAttribute(ConversationRequestDiagnostics.ATTRIBUTE), exception.getClass().getName());
        }
        return ResponseEntity.status(status).body(body);
    }
}
