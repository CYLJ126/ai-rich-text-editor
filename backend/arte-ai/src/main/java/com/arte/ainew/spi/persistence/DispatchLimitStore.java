package com.arte.ainew.spi.persistence;

import com.arte.ainew.config.NewAiExecutionProperties.DispatchLimits;
import com.arte.ainew.pojo.execution.OutboxMessage;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * DB 时钟和 Outbox fencing 仲裁并发额度与固定窗口请求频率，跨实例共享。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 22:05 ✾
 */
public interface DispatchLimitStore {

    record Decision(boolean granted, Duration retryAfter) {
    }

    Mono<Decision> acquireDispatchPermit(OutboxMessage message, String modelKey, DispatchLimits limits);

    Mono<Void> releaseDispatchPermit(OutboxMessage message);
}
