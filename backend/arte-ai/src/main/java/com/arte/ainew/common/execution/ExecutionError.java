package com.arte.ainew.common.execution;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.util.Objects;

/**
 * 可持久化的安全错误事实，不保存原始异常、凭据、供应商正文或用户全文；可重试性不是自动重试许可。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record ExecutionError(String code, Phase phase, boolean retryable, SideEffect sideEffect,
                             Certainty certainty, String correlationId) implements Serializable {
    public enum Phase {VALIDATION, AUTHORIZATION, ADMISSION, DISPATCH, INVOCATION, OUTPUT, PERSISTENCE, SETTLEMENT}

    public enum SideEffect {NONE, POSSIBLE, CONFIRMED}

    public enum Certainty {KNOWN, UNKNOWN}

    public ExecutionError {
        ContractChecks.id(code, "code");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(sideEffect, "sideEffect");
        Objects.requireNonNull(certainty, "certainty");
        ContractChecks.id(correlationId, "correlationId");
    }
}
