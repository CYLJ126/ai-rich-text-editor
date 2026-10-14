package com.arte.ainew.application.execution;

import com.arte.ainew.common.execution.LiveTextDelta;
import com.arte.ainew.pojo.execution.OutboxMessage;
import reactor.core.publisher.Mono;

/**
 * 非耐久通知传输；成功返回后才可 ACK EVENT Outbox，权威数据（authoritative data，或 source of truth，即事实依据）始终由数据库重放（执行事实以数据库中已提交的事件为准）。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 00:06 ✾
 */
@FunctionalInterface
public interface ExecutionEventBroadcast {

    Mono<Void> publish(OutboxMessage message);

    /**
     * 预览没有耐久游标；LOCAL 模式已直接分发，Redis 模式另行发布原始文字增量。
     */
    default Mono<Void> publishText(LiveTextDelta delta) {
        return Mono.empty();
    }

    /**
     * 无任务内容的派发检查提示；不等待消费者领取，不作为发送许可，LOCAL 实现无需广播。
     */
    default Mono<Void> publishDispatchWakeup() {
        return Mono.empty();
    }
}
