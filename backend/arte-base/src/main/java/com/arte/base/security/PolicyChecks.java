package com.arte.base.security;

import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.execution.ResultCertainty;
import com.arte.base.model.execution.SideEffectStatus;
import com.arte.base.model.security.*;
import com.arte.base.validation.ContractChecks;

import java.time.Instant;

/**
 * 强制策略检查的公共规则，只验证返回契约及时间窗口，不实现领域权限或网络出口策略。
 */
public final class PolicyChecks {

    private PolicyChecks() {
    }

    public static void requireActive(ExecutionContext context, Instant now, String stage) {
        ContractChecks.required(context, "context");
        ContractChecks.required(now, "now");
        if (context.isExpiredAt(now)) {
            throw failure(CommonErrorCode.DEADLINE_EXCEEDED, context, stage, false, null);
        }
    }

    public static AuthorizationDecision requireAuthorization(AuthorizationRequest expected,
                                                             AuthorizationDecision actual, Instant now) {
        ContractChecks.required(expected, "request");
        if (actual == null || !expected.equals(actual.request())) {
            throw unavailable(expected.context(), "authorization", null);
        }
        requireAllowed(actual.policy(), expected.context(), now, "authorization");
        return actual;
    }

    public static EgressDecision requireEgress(EgressRequest expected, EgressDecision actual, Instant now) {
        ContractChecks.required(expected, "request");
        if (actual == null || !expected.equals(actual.request())) {
            throw unavailable(expected.context(), "egress", null);
        }
        requireAllowed(actual.policy(), expected.context(), now, "egress");
        return actual;
    }

    private static void requireAllowed(PolicyDecision decision, ExecutionContext context, Instant now, String stage) {
        requireActive(context, now, stage);
        if (now.isBefore(decision.evaluatedAt())
                || (decision.validUntil() != null && !now.isBefore(decision.validUntil()))) {
            throw unavailable(context, stage, null);
        }
        if (decision.outcome() == PolicyOutcome.DENY) {
            throw failure(CommonErrorCode.UNAUTHORIZED, context, stage, false, null);
        }
        if (decision.outcome() != PolicyOutcome.ALLOW) {
            throw unavailable(context, stage, null);
        }
    }

    /**
     * 提供者故障不转为允许；cause 保留在异常诊断中，不进入公开错误信封。
     */
    public static BaseException unavailable(ExecutionContext context, String stage, Throwable cause) {
        return failure(CommonErrorCode.POLICY_UNAVAILABLE, context, stage, true, cause);
    }

    private static BaseException failure(CommonErrorCode code, ExecutionContext context, String stage,
                                         boolean retryable, Throwable cause) {
        return new BaseException(ExecutionError.of(code, stage, retryable,
                SideEffectStatus.NONE, ResultCertainty.CONFIRMED, context.traceId()), cause);
    }
}
