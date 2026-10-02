package com.arte.base.model.execution;

/**
 * 稳定错误信封；错误码和失败阶段由模块扩展，不包含凭据或敏感全文。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ExecutionError(
        String code,
        String failureStage,
        boolean retryable,
        SideEffectStatus sideEffectStatus,
        ResultCertainty resultCertainty,
        String correlationId
) {
}
