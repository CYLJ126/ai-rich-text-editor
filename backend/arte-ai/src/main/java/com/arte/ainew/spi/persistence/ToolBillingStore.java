package com.arte.ainew.spi.persistence;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.tool.ToolCharge;
import reactor.core.publisher.Mono;

/**
 * 受信适配器已观察的账单按原 Attempt 不可变保存；不执行工具或重发请求。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 14:00 ✾
 */
public interface ToolBillingStore {

    Mono<Boolean> toolPriceBlocked(com.arte.ainew.common.reference.DefinitionRef rate);

    Mono<Void> recordToolCharge(ExecutionOwner owner, String invocationId, String attemptId, ToolCharge charge);

    Mono<ToolCharge> toolCharge(ExecutionOwner owner, String invocationId, String attemptId);
}
