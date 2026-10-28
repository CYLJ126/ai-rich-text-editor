package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.core.pojo.value.StructuredValue;

import java.time.Duration;
import java.util.Objects;

/**
 * 独立 TOOL 调用的业务意图；由可信入口传入，不允许指定工具版本、执行器、费率或预算账户。
 *
 * @param bindingId        当前主体的稳定绑定 ID，重放时沿用原受理解析结果
 * @param arguments        原始结构化参数，服务端校验但不补值或改写
 * @param requestedTimeout 可选请求总时长，与产品及入口上限求交；null 继承产品上限
 * @param maxAttempts      可选尝试上限，包含首次，只能收紧有效上限
 * @param maxOutputBytes   可选结果信封 UTF-8 字节上限，只能收紧有效上限
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 16:30 ✾
 */
public record ToolAdmissionInput(String bindingId, StructuredValue.ObjectValue arguments, Duration requestedTimeout,
                                 Integer maxAttempts, Long maxOutputBytes,
                                 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String budgetRef) {
    public ToolAdmissionInput(String bindingId, StructuredValue.ObjectValue arguments, Duration requestedTimeout, Integer maxAttempts, Long maxOutputBytes) {
        this(bindingId, arguments, requestedTimeout, maxAttempts, maxOutputBytes, null);
    }

    public ToolAdmissionInput {
        ContractChecks.id(bindingId, "bindingId");
        ContractChecks.optionalId(budgetRef, "tool budgetRef");
        Objects.requireNonNull(arguments);
        if (requestedTimeout != null) ContractChecks.require(requestedTimeout.compareTo(Duration.ofSeconds(1)) >= 0
                && requestedTimeout.compareTo(Duration.ofHours(1)) <= 0, "Invalid tool requested timeout");
        if (maxAttempts != null) ContractChecks.range(maxAttempts, "maxAttempts", 1, 10);
        if (maxOutputBytes != null)
            ContractChecks.range(maxOutputBytes, "maxOutputBytes", 1, ToolExecutionPolicy.MAX_OUTPUT_BYTES);
    }
}
