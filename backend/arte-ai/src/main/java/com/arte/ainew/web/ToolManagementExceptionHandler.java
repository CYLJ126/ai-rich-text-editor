package com.arte.ainew.web;

import com.arte.ainew.application.execution.DispatchSchedulingException;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.web.controller.NewAiToolController;
import com.arte.ainew.web.controller.NewAiToolSubmissionController;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.i18n.MessageUtils;
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
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    private static final Pattern FIELD_PATH = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*|\\[[0-9]{0,6}\\])*");

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResultContext<Void>> handle(Exception error, HttpServletRequest request, Locale locale) {
        if (error instanceof MissingRequestHeaderException missing) {
            return missingHeader(missing, request, locale);
        }
        if (error instanceof MethodArgumentNotValidException invalid) {
            return invalidFields(invalid, request, locale);
        }
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
        } else if (error instanceof HttpMessageNotReadableException
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

    /**
     * Spring 在进入 Controller 前校验必填请求头；仅输出服务端声明的头名称，不记录请求值或异常原文。
     */
    private ResponseEntity<ResultContext<Void>> missingHeader(MissingRequestHeaderException error, HttpServletRequest request, Locale locale) {
        var code = ResultCodeEnum.AI_TOOL_INVALID_REQUEST;
        boolean converted = error.isMissingAfterConversion();
        String reason = converted ? "INVALID_REQUEST_HEADER" : "MISSING_REQUEST_HEADER";
        String message = converted ? "result.ai.tool.invalidHeader" : "result.ai.tool.missingHeader";
        log.warn("AI_TOOL_HTTP rejected method={} path={} status=400 code={} type={} reason={} header={}",
                request.getMethod(), request.getRequestURI(), code.getCode(), error.getClass().getSimpleName(), reason, error.getHeaderName());
        var body = ResultContext.<Void>fail(code, locale).setDesc(MessageUtils.get(locale, message, error.getHeaderName()));
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 仅投影有界字段路径和固定约束原因；不读取 rejectedValue 或可能带业务原文的校验消息。
     */
    private ResponseEntity<ResultContext<Void>> invalidFields(MethodArgumentNotValidException error, HttpServletRequest request, Locale locale) {
        var code = ResultCodeEnum.AI_TOOL_INVALID_REQUEST;
        var fields = error.getBindingResult().getFieldErrors().stream().map(this::invalidField).distinct()
                .sorted(Comparator.comparing(InvalidField::field).thenComparing(InvalidField::rule)).limit(16).toList();
        if (fields.isEmpty()) fields = List.of(new InvalidField("$request", "INVALID"));
        String diagnostics = fields.stream().map(field -> field.field() + ":" + field.rule()).collect(Collectors.joining(", "));
        log.warn("AI_TOOL_HTTP rejected method={} path={} status=400 code={} type={} reason=INVALID_REQUEST_FIELDS fields=[{}]",
                request.getMethod(), request.getRequestURI(), code.getCode(), error.getClass().getSimpleName(), diagnostics);
        String details = fields.stream().map(field -> field.field() + " (" + MessageUtils.get(locale,
                "result.ai.tool.field." + field.rule().toLowerCase(Locale.ROOT)) + ")").collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(ResultContext.<Void>fail(code, locale)
                .setDesc(MessageUtils.get(locale, "result.ai.tool.invalidFields", details)));
    }

    private InvalidField invalidField(FieldError error) {
        // Map 键等动态片段不作为错误详情输出；只保留普通字段和有界数组索引，阻止日志换行及动态键泄漏。
        String field = error.getField();
        if (field.length() > 128 || !FIELD_PATH.matcher(field).matches()) field = "$request";
        String rule = switch (error.getCode() == null ? "" : error.getCode()) {
            case "NotNull", "NotBlank", "NotEmpty" -> "REQUIRED";
            case "Pattern", "Email" -> "FORMAT";
            case "Min", "Max", "DecimalMin", "DecimalMax", "Positive", "PositiveOrZero", "Negative", "NegativeOrZero" ->
                    "RANGE";
            case "Size" -> "SIZE";
            default -> "INVALID";
        };
        return new InvalidField(field, rule);
    }

    /**
     * 字段路径及白名单约束分类，供响应翻译和安全日志共用；不持有提交值。
     */
    private record InvalidField(String field, String rule) {
    }
}
