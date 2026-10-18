package com.arte.ainew.pojo.execution;

import com.arte.ainew.common.validation.ContractChecks;

/**
 * 受信受理阶段固定的调度事实；不是 HTTP 输入，不随请求缩短 timeout 把长工具伪装为短工具。
 * GENERATION 的 providerKey 来自固定连接；TOOL 来自已发布提供者身份。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 21:47 ✾
 */
public record DispatchPlan(OutboxMessage.WorkClass workClass, String providerKey) {

    public DispatchPlan {
        java.util.Objects.requireNonNull(workClass);
        ContractChecks.id(providerKey, "providerKey");
        ContractChecks.require(workClass != OutboxMessage.WorkClass.OUTCOME_CHECK, "Outcome checks are not invocation submissions");
    }
}
