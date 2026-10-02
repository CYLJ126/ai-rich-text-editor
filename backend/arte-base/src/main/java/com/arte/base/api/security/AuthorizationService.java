package com.arte.base.api.security;

import com.arte.base.model.security.AuthorizationDecision;
import com.arte.base.model.security.AuthorizationRequest;
import com.arte.base.security.PolicyChecks;
import com.arte.base.validation.ContractChecks;

import java.time.Clock;

/**
 * 主体与资源动作授权端口，每次 evaluate 均核对当前主体、租户／空间归属和适用策略。
 * 有效权限为发起者、实际服务执行者、资源共享、应用／绑定及任务授权的交集。
 * 未接入领域、授权状态未知或策略不可用时返回 INDETERMINATE，不默认授予权限。
 * 领域版本及业务校验仍由所属模块负责；动作之间不隐式继承权限。
 */
@FunctionalInterface
public interface AuthorizationService {

    /**
     * 返回绑定本次完整请求的决策，用于解释判定或展示预检结果，不代表已执行业务动作。
     * 提供者须在关键操作边界检查当前策略，不能仅使用历史发布配置或任务中的范围声明。
     * 正常拒绝用 DENY 表达；无法确定用 INDETERMINATE 表达，不返回 null。
     */
    AuthorizationDecision evaluate(AuthorizationRequest request);

    /**
     * 操作边界的强制检查；每次重新评估，返回后仍须立即进入领域校验及事务。
     */
    default AuthorizationDecision requireAuthorized(AuthorizationRequest request) {
        return requireAuthorized(request, Clock.systemUTC());
    }

    /**
     * 使用注入的时钟，在提供者调用前后检查期限，并验证请求绑定及决策有效期。
     * 拒绝抛 UNAUTHORIZED；无结果、异常、不匹配或失效抛 POLICY_UNAVAILABLE；
     * 执行期限届满抛 DEADLINE_EXCEEDED。错误由 BaseException 承载。
     */
    default AuthorizationDecision requireAuthorized(AuthorizationRequest request, Clock clock) {
        ContractChecks.required(request, "request");
        ContractChecks.required(clock, "clock");
        PolicyChecks.requireActive(request.context(), clock.instant(), "authorization");
        AuthorizationDecision decision;
        try {
            decision = evaluate(request);
        } catch (RuntimeException providerFailure) {
            throw PolicyChecks.unavailable(request.context(), "authorization", providerFailure);
        }
        return PolicyChecks.requireAuthorization(request, decision, clock.instant());
    }
}
