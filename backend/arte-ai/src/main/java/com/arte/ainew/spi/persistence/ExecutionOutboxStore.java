package com.arte.ainew.spi.persistence;

import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.pojo.execution.StoreOutcome;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * 耐久派发／事件通知 Outbox 的消费存储接口，供可信内部消费者使用。
 * <p>
 * DISPATCH 指向待处理的逻辑调用，EVENT 指向已耐久提交的执行事件。
 * 消息随受理或事件提交事务写入；本接口负责领取、核验、续租、延后排队和消费确认，不负责业务授权。
 * <p>
 * 未确认消息在领取租约到期后可被重新领取；延后排队的消息须等到下次可领取时间。
 * 消费者持续运行时按至少一次处理，因此可能重复消费，须按消息身份及业务防重键处理重放。
 * Outbox 领取与 Attempt 执行租约相互独立；领取或 ACK 均不能证明模型仅执行一次，
 * 外部请求仍须依靠 Attempt、发送事实及 fencing 校验防重，结果未知时不能盲目重发。
 * <p>
 * 存储操作返回冷 Mono，每次订阅重新执行，不缓存首次结果；持久化变更在事务提交后返回。
 * 可预期的消息不存在或领取失效通过 StoreOutcome 表达，数据库等非预期故障通过 onError 传播。
 * 参数不合法可能在方法调用时直接抛出异常；调用方不能将未订阅的 Mono 视为操作已完成。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:11 ✾
 */
public interface ExecutionOutboxStore {

    /**
     * 原子领取指定类型中尚未确认、且已到可领取时间的消息。
     * <p>
     * 可领取时间包括原领取租约到期时间或 {@link #defer(OutboxMessage, Duration)} 设置的延迟时间。
     * 领取时保存 workerId，递增该消息的 fencingToken，并以数据库当前时间加 lease 设置租约到期时间。
     * 同一有效领取不能被其他消费者抢占，租约到期后重新领取会分配更大的 token。
     * <p>
     * 领取不表示消费完成；完成本条消息的工作后调用 {@link #acknowledge(OutboxMessage)}，
     * 需要延后处理则调用 defer，不应将仍需重试的工作直接 ACK。
     * 本方法按消息类型领取，可包含不同归属的消息；limit 仅限制本批数量，不是全局并发额度。
     *
     * @param kind     消息类型：DISPATCH（执行派发）或 EVENT（事件通知），不能为空
     * @param workerId 当前消费者实例／运行实例的唯一标识，非空白且不超过 256 字符
     * @param lease    领取租约时长，范围为 1 秒至 5 分钟（含边界），与任务执行截止时间独立
     * @param limit    本次最多领取的消息数，范围为 1 至 256；实际数量可能更少
     * @return 事务提交后已领取消息的不可变列表，包含归属、Worker、token 及租约到期时间；
     * 无可领取消息时返回空列表，而不是 Mono.empty()
     */
    Mono<List<OutboxMessage>> claim(OutboxMessage.Kind kind, String workerId, Duration lease, int limit);

    /**
     * 只读核验消息当前领取是否有效，不续租或确认消费。
     * <p>
     * 按 messageId 定位，比较 invocationId、owner、kind、eventSequence、workerId 和 fencingToken，
     * 并检查消息尚未确认且数据库记录中的租约尚未到期。
     * 传入对象的 leaseExpiresAt 不参与到期判断，因此续租后仍可用原对象核验同一次领取。
     * 校验成功只反映本次检查时的有效性，不保证后续操作时租约仍有效，后续写操作须再次校验。
     *
     * @param message 由 claim 返回的消息领取身份，不能为空
     * @return 有效时返回 APPLIED 及原 message（不是最新租约快照）；消息不存在返回 NOT_FOUND，
     * 身份不符、已确认、已延后排队或租约已过期返回 LEASE_LOST
     */
    Mono<StoreOutcome<OutboxMessage>> validateClaim(OutboxMessage message);

    /**
     * 为尚未确认且仍有效的同一次消息领取重新设置租约到期时间。
     * <p>
     * 核验条件与 {@link #validateClaim(OutboxMessage)} 一致；到期时间设为数据库当前时间加 lease，
     * 不是在原到期时间上追加时长，不修改 Worker 或 token，也不能复活失效领取。
     * 已有关联的派发并发许可在同一事务中同步到期时间；不会新建许可或续租 Attempt。
     *
     * @param message 当前领取身份，不能为空；传入对象的旧到期时间不影响同一次领取的续租
     * @param lease   从数据库当前时间开始计算的租约时长，范围为 1 秒至 5 分钟（含边界）
     * @return 成功返回 APPLIED 及携带新到期时间的消息；消息不存在返回 NOT_FOUND，
     * 身份不符、已确认、已延后排队或租约已过期返回 LEASE_LOST
     */
    Mono<StoreOutcome<OutboxMessage>> renewClaim(OutboxMessage message, Duration lease);

    /**
     * 释放当前消息领取，并将消息延后到数据库当前时间加 delay 后重新参与领取。
     * <p>
     * 用于派发限流或安全重试退避等延后处理；核验条件与 validateClaim 一致。
     * 成功后清除 Worker，保留当前 token，消息仍未确认；即使 delay 为零，原领取身份也立即失效。
     * 后续须重新 claim 才能获得有效 Worker 和更大的 token；原身份不能继续核验、续租、延后或 ACK。
     * <p>
     * 本方法不提交调用终态、不结算预算，也不释放并发许可；这些工作由相应应用流程另行处理。
     * 当前存储不限制消息类型，派发 Worker 使用它延后 DISPATCH。
     *
     * @param message 当前有效领取身份，不能为空
     * @param delay   下次可领取前的等待时间，不能为空，范围为 0 至 1 小时（含边界）
     * @return 成功返回 APPLIED 及原 message，仅表示延后操作已提交，该对象不再代表有效领取；
     * 消息不存在返回 NOT_FOUND，身份不符、已确认或领取已失效返回 LEASE_LOST；
     * 使用原身份重复 defer 返回 LEASE_LOST，不返回幂等重放结果
     */
    Mono<StoreOutcome<OutboxMessage>> defer(OutboxMessage message, Duration delay);

    /**
     * 在本条消息的消费工作完成后确认消息，使其不再参与领取。
     * <p>
     * DISPATCH 在对应耐久处理完成后确认，EVENT 在事件通知发布成功后确认。
     * 存储只记录消费确认，不改变 Invocation／Attempt 状态，不结算预算或释放并发许可；
     * ACK 不表示模型成功或浏览器已接收事件，也不要求当前存在在线订阅者。
     * <p>
     * 首次确认须核验完整领取身份且数据库租约仍有效。
     * 若同一完整身份已确认，则先返回 REPLAYED，即使租约随后过期也可重放；
     * 不同身份、已延后排队或已被重新领取的旧身份不能确认消息。
     *
     * @param message 要确认的消息领取身份，不能为空
     * @return 首次确认返回 APPLIED，同一身份已确认返回 REPLAYED，两者均携带原 message；
     * 消息不存在返回 NOT_FOUND，身份不符或尚未确认但租约已失效返回 LEASE_LOST
     */
    Mono<StoreOutcome<OutboxMessage>> acknowledge(OutboxMessage message);
}
