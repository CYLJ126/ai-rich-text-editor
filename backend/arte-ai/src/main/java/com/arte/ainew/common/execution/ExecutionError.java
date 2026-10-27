package com.arte.ainew.common.execution;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.util.Objects;

/**
 * 可持久化的安全错误事实
 * <p>
 * 不保存原始异常、凭据、供应商正文或用户全文；可重试性不是自动重试许可。
 *
 * @param code 稳定的错误标识码，用于分类、展示映射及处理分支，不保存原始异常消息或敏感正文。
 * @param phase 失败发生的阶段，不能为空：校验、授权、受理、派发、调用、输出处理、持久化或结算。
 * @param retryable 该错误是否具备可重试性；自动重试仍须核对发送事实、副作用、结果确定性、预算及重试策略，不能仅凭此标志重发。
 * @param sideEffect 本次执行的实际副作用情况，不能为空：NONE 表示未发生，POSSIBLE 表示可能发生，CONFIRMED 表示已确认发生；不表示业务整体成功。
 * @param certainty 本次执行结果的确定性，不能为空：KNOWN 表示结果已知，UNKNOWN 表示结果尚无法确认，须按核对流程处理，不能直接当作未执行。
 * @param correlationId 用于关联日志、追踪及排查的安全标识，当前调用链通常使用 traceId；不能为空，不承载凭据或错误正文。
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
