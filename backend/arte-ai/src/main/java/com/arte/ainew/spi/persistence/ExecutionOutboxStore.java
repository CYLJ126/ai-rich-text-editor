package com.arte.ainew.spi.persistence;

import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.pojo.execution.StoreOutcome;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * 耐久派发／事件发布队列；崩溃后租约到期可重领，交付至少一次，外部调用仍须 Attempt 防重。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:11 ✾
 */
public interface ExecutionOutboxStore {
    Mono<List<OutboxMessage>> claim(OutboxMessage.Kind kind, String workerId, Duration lease, int limit);

    /**
     * 发布成功后确认，比较消息 Worker、token、有效租约；ACK 不代表业务执行终态。
     */
    Mono<StoreOutcome<OutboxMessage>> acknowledge(OutboxMessage message);
}
