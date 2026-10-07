package com.arte.ainew.application.execution;

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
}
