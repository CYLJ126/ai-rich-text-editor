package com.arte.base.model.error;

/**
 * 通用机制错误；AI 和文章专有错误由各自模块扩展，不依赖国际化组件。
 */
public enum CommonErrorCode implements ErrorCode {
    INVALID_ARGUMENT("arte.common.invalid_argument"),
    UNAUTHORIZED("arte.common.unauthorized"),
    POLICY_UNAVAILABLE("arte.common.policy_unavailable"),
    NOT_FOUND("arte.common.not_found"),
    UNSUPPORTED("arte.common.unsupported"),
    RANGE_INVALID("arte.common.range_invalid"),
    VERSION_CONFLICT("arte.common.version_conflict"),
    IDEMPOTENCY_CONFLICT("arte.common.idempotency_conflict"),
    RATE_LIMITED("arte.common.rate_limited"),
    BUSY("arte.common.busy"),
    DEADLINE_EXCEEDED("arte.common.deadline_exceeded"),
    INTERRUPTED("arte.common.interrupted"),
    OUTCOME_UNKNOWN("arte.common.outcome_unknown"),
    CURSOR_EXPIRED("arte.common.cursor_expired"),
    INTERNAL_ERROR("arte.common.internal_error");

    private final String code;

    CommonErrorCode(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
