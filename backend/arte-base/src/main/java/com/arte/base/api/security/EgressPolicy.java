package com.arte.base.api.security;

import com.arte.base.model.security.EgressDecision;
import com.arte.base.model.security.EgressRequest;
import com.arte.base.security.PolicyChecks;
import com.arte.base.validation.ContractChecks;

import java.time.Clock;

/**
 * 资料外发策略端口，与资源动作授权分别调用；读取或编辑许可不默认允许外发。
 * 每次 evaluate 核对实际资料、任务范围、用户同意及共享策略、用途、目的地和当前连接配置。
 * consentRef 须核对归属及覆盖范围，不能仅因引用存在而允许发送。
 * 空来源只表示没有业务资料来源，仍须检查实际消息内容、目的地、用途和用户授权。
 */
@FunctionalInterface
public interface EgressPolicy {

    /**
     * 返回绑定本次完整请求的决策；无法确认、提供者未接入或资料范围不匹配时不得返回 ALLOW。
     * 授权覆盖完整外发集合，不作静默部分放行；需要裁剪时重新准备内容、摘要及请求。
     * 资源读取／处理授权还须经 AuthorizationService 核对。
     * 此端口不建立网络连接；实际地址、DNS、重定向与出口检查归 ConnectionRuntime。
     */
    EgressDecision evaluate(EgressRequest request);

    /**
     * 发送边界的强制检查，每次重新评估；只返回决策，不发送数据。
     */
    default EgressDecision requireAllowed(EgressRequest request) {
        return requireAllowed(request, Clock.systemUTC());
    }

    /**
     * 调用前后检查期限；仅放行绑定本次请求且处于有效期内的 ALLOW。
     * 拒绝、策略不可用及期限届满分别按授权端口相同的稳定错误契约返回。
     * 调用方必须保证随后发送的实际来源、内容摘要、连接版本及目的地与本次请求一致。
     */
    default EgressDecision requireAllowed(EgressRequest request, Clock clock) {
        ContractChecks.required(request, "request");
        ContractChecks.required(clock, "clock");
        PolicyChecks.requireActive(request.context(), clock.instant(), "egress");
        EgressDecision decision;
        try {
            decision = evaluate(request);
        } catch (RuntimeException providerFailure) {
            throw PolicyChecks.unavailable(request.context(), "egress", providerFailure);
        }
        return PolicyChecks.requireEgress(request, decision, clock.instant());
    }
}
