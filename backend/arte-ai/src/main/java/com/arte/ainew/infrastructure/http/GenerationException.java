package com.arte.ainew.infrastructure.http;

import com.arte.ainew.common.execution.ExecutionError;

import java.io.Serial;

/**
 * 安全的内部失败分类，不携带响应正文、请求、凭据或底层异常消息。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
public final class GenerationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = -5351422049632270296L;

    private final ExecutionError.Phase phase;
    private final boolean retryable;
    private final ExecutionError.SideEffect sideEffect;
    private final ExecutionError.Certainty certainty;

    public GenerationException(String code, ExecutionError.Phase phase, boolean retryable,
                               ExecutionError.SideEffect sideEffect, ExecutionError.Certainty certainty) {
        super(code);
        this.phase = phase;
        this.retryable = retryable;
        this.sideEffect = sideEffect;
        this.certainty = certainty;
    }

    public ExecutionError error(String correlationId) {
        return new ExecutionError(getMessage(), phase, retryable, sideEffect, certainty, correlationId);
    }

    public static GenerationException transientBeforeSend(String code) {
        return new GenerationException(code, ExecutionError.Phase.DISPATCH, true,
                ExecutionError.SideEffect.NONE, ExecutionError.Certainty.KNOWN);
    }

    public static GenerationException beforeSend(String code) {
        return new GenerationException(code, ExecutionError.Phase.DISPATCH, false,
                ExecutionError.SideEffect.NONE, ExecutionError.Certainty.KNOWN);
    }

    public static GenerationException output(String code) {
        return new GenerationException(code, ExecutionError.Phase.OUTPUT, false,
                ExecutionError.SideEffect.POSSIBLE, ExecutionError.Certainty.UNKNOWN);
    }

    public static GenerationException http(int status) {
        boolean rejected = status == 400 || status == 401 || status == 403 || status == 404
                || status == 413 || status == 422 || status == 429;
        return new GenerationException("PROVIDER_HTTP_" + status, ExecutionError.Phase.INVOCATION,
                status == 429 || status >= 500, rejected ? ExecutionError.SideEffect.NONE : ExecutionError.SideEffect.POSSIBLE,
                rejected ? ExecutionError.Certainty.KNOWN : ExecutionError.Certainty.UNKNOWN);
    }
}
