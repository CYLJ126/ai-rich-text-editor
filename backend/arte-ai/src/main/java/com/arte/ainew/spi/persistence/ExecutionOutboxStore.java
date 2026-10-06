package com.arte.ainew.spi.persistence;

import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.pojo.execution.StoreOutcome;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * 耐久派发／事件发布队列
 * <p>
 * 崩溃后租约到期可重领，交付至少一次，外部调用仍须 Attempt 防重。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:11 ✾
 */
public interface ExecutionOutboxStore {

    /**
     * 原子领取指定类型中尚未确认、且当前没有有效租约的 Outbox 消息。
     * DISPATCH 供执行 Worker 派发调用，EVENT 供事件发布消费者发布已提交事件。
     * <p>
     * 领取时记录 workerId、递增的 fencingToken 和基于数据库时钟的租约到期时间；
     * 租约到期后消息可重新领取，因此消费者须按 messageId 防重，实际调用还须检查 Attempt 执行归属。
     * 领取不表示处理完成，耐久处理或发布成功后须调用 {@link #acknowledge(OutboxMessage)}。
     * <p>
     * 返回冷 Mono，每次订阅执行一次领取；没有可领取消息时返回空列表，而不是 Mono.empty()。
     *
     * @param kind     领取类型：DISPATCH（执行派发）或 EVENT（事件发布），不能为空
     * @param workerId 当前消费者实例／运行实例的唯一标识，非空且不超过 256 字符
     * @param lease    本次领取的租约时长，范围为 1 秒至 5 分钟（含边界），不是任务执行超时
     * @param limit    单次最大领取数量，范围为 1 至 256；实际返回数量可能更少
     * @return 事务提交后已领取消息的不可变列表，各消息携带消费者标识、fencing token 和租约到期时间
     */
    Mono<List<OutboxMessage>> claim(OutboxMessage.Kind kind, String workerId, Duration lease, int limit);

    /**
     * 按数据库时钟及完整消息身份核验当前领取；已确认或失去租约返回 LEASE_LOST。
     */
    Mono<StoreOutcome<OutboxMessage>> validateClaim(OutboxMessage message);

    /**
     * 未到期的同一消费者／token 才能续租，不改变 token，不复活已经失效的领取。
     */
    Mono<StoreOutcome<OutboxMessage>> renewClaim(OutboxMessage message, Duration lease);

    /**
     * 派发消息已耐久处理／移交，或事件发布成功后确认，比较消息 Worker、token、有效租约；
     * ACK 不代表业务执行终态。
     */
    Mono<StoreOutcome<OutboxMessage>> acknowledge(OutboxMessage message);
}
