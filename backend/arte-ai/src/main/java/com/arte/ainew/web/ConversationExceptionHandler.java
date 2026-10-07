package com.arte.ainew.web;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.web.controller.NewAiBudgetController;
import com.arte.ainew.web.controller.NewAiChatController;
import com.arte.ainew.web.controller.NewAiConversationController;
import com.arte.ainew.web.controller.NewAiInvocationController;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.IResult;
import com.arte.core.pojo.PageView;
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
 * 同步参数错误和 Mono 异步错误统一包装，分页失败保持 PageView 的响应结构。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 15:38 ✾
 */
@RestControllerAdvice(assignableTypes = {NewAiConversationController.class, NewAiChatController.class,
        NewAiInvocationController.class, NewAiBudgetController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class ConversationExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<IResult> handle(Exception exception, HttpServletRequest request, Locale locale) {
        var status = HttpStatus.INTERNAL_SERVER_ERROR;
        ResultCodeEnum code = null;
        if (exception instanceof AuthenticationException) {
            status = HttpStatus.UNAUTHORIZED;
            code = ResultCodeEnum.FAIL;
        } else if (exception instanceof AccessDeniedException) {
            status = HttpStatus.FORBIDDEN;
            code = ResultCodeEnum.FAIL;
        } else if (exception instanceof MethodArgumentNotValidException || exception instanceof HttpMessageNotReadableException
                || exception instanceof ServletRequestBindingException || exception instanceof IllegalArgumentException) {
            status = HttpStatus.BAD_REQUEST;
            code = ResultCodeEnum.FAIL;
        } else if (exception instanceof AdmissionException admission) {
            code = admission.getResultCode();
            status = switch (code) {
                case AI_CONVERSATION_NOT_FOUND, AI_TURN_NOT_FOUND, AI_NOT_FOUND, AI_BUDGET_NOT_AVAILABLE ->
                        HttpStatus.NOT_FOUND;
                case AI_VERSION_CONFLICT, AI_IDEMPOTENCY_CONFLICT, AI_CONVERSATION_BUSY, AI_RESULT_NOT_AVAILABLE ->
                        HttpStatus.CONFLICT;
                case AI_CURSOR_EXPIRED -> HttpStatus.GONE;
                default -> HttpStatus.BAD_REQUEST;
            };
        }
        String path = request.getRequestURI();
        boolean paged = path.endsWith("/listConversations") || path.endsWith("/queryTurnsOfConversation");
        IResult body = paged ? (code == null ? PageView.exception(exception, locale) : PageView.fail(code, locale))
                : (code == null ? ResultContext.exception(exception, locale) : ResultContext.fail(code, locale));
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            // 不记录请求正文、快照或底层异常文本，它们可能包含用户输入或数据库参数。
            log.error("New AI HTTP failure, type={}", exception.getClass().getName());
        } else {
            // 可预期拒绝也留诊断线索；不写请求参数、异常原文、认证头或用户／模型正文。
            log.warn("New AI HTTP rejected, method={}, path={}, status={}, code={}, type={}",
                    request.getMethod(), path, status.value(), code.getCode(), exception.getClass().getName());
        }
        return ResponseEntity.status(status).body(body);
    }
}
