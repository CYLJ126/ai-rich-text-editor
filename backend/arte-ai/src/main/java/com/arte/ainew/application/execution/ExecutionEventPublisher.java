package com.arte.ainew.application.execution;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.support.InvocationTiming;
import com.arte.ainew.config.NewAiExecutionProperties;
import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.spi.persistence.ExecutionOutboxStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EVENT Outbox 的至少一次发布器
 * <p>
 * 提交后立即唤醒，统一 5 秒扫描恢复遗漏唤醒和过期租约。
 * 不为每个 SSE 连接查询数据库；发布指针成功后 ACK，即使当前没有浏览器也可确认。
 * ACK 失败留待重新领取，重复指针由订阅端的耐久 sequence 去重。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 00:06 ✾
 */
@Slf4j
public final class ExecutionEventPublisher implements SmartLifecycle {

    /**
     * 每次最多领取的 EVENT 消息数；一批成功发布并确认满 64 条后，主动唤醒下一次领取。
     */
    public static final int BATCH_SIZE = 64;

    /**
     * 本发布器统一恢复扫描的触发间隔，用于检查遗漏唤醒及租约已到期的未确认消息，不按 SSE 连接创建定时器。
     */
    public static final Duration RECOVERY_INTERVAL = Duration.ofSeconds(5);

    /**
     * 耐久 Outbox 存储，负责原子领取、校验领取身份与租约，以及发布成功后的确认。
     */
    private final ExecutionOutboxStore executionOutboxStore;

    /**
     * 当前节点的内存唤醒通道：接收事务提交提示，并在满批处理后触发继续领取。
     */
    private final LocalExecutionEventNotifier localExecutionEventNotifier;

    /**
     * 执行配置，本发布器使用 Outbox 租约时长和 Worker 自动启动开关。
     */
    private final NewAiExecutionProperties properties;

    /**
     * 恢复定时器及唤醒处理所使用的调度器；数据库操作的具体执行线程由存储适配器管理。
     */
    private final Scheduler scheduler;

    /**
     * 通知传输实现，可为本地分发或 Redis 跨实例广播；正常完成后才允许确认对应 Outbox。
     */
    private final ExecutionEventBroadcast executionEventBroadcast;

    /**
     * 本发布器对象的唯一领取者标识，写入 Outbox 领取记录，与其他实例及派发 Worker 区分。
     */
    private final String workerId = "events-" + UUID.randomUUID();

    /**
     * 本对象是否已有领取批次正在处理，以原子 CAS 防止手动 pollOnce 与自动循环重复开启批次。
     */
    private final AtomicBoolean polling = new AtomicBoolean();

    /**
     * 自动发布循环的 Reactor 订阅句柄，stop 时取消唤醒监听、恢复定时器及正在处理的响应式流程。
     */
    private volatile Disposable loop;

    /**
     * 发布循环的生命周期运行标志，由 start、stop 及循环终止回调更新，供 Spring 查询。
     */
    private volatile boolean running;

    public ExecutionEventPublisher(ExecutionOutboxStore executionOutboxStore, LocalExecutionEventNotifier localExecutionEventNotifier,
                                   NewAiExecutionProperties properties, Scheduler scheduler) {
        this(executionOutboxStore, localExecutionEventNotifier, properties, scheduler, message -> Mono.fromRunnable(() -> localExecutionEventNotifier.publish(message)));
    }

    public ExecutionEventPublisher(ExecutionOutboxStore executionOutboxStore, LocalExecutionEventNotifier localExecutionEventNotifier,
                                   NewAiExecutionProperties properties, Scheduler scheduler, ExecutionEventBroadcast executionEventBroadcast) {
        this.executionEventBroadcast = executionEventBroadcast;
        this.executionOutboxStore = executionOutboxStore;
        this.localExecutionEventNotifier = localExecutionEventNotifier;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    /**
     * 执行一批 EVENT Outbox 发布：领取最多 BATCH_SIZE 条，逐条校验租约、发布通知，再确认消息。
     * 可由自动循环调用，也可在关闭自动 Worker 的测试或手动处理场景中订阅此 Mono 执行一次。
     * <p>
     * 返回冷 Mono，实际订阅时才尝试取得本对象的批次处理资格并领取消息。
     * 单条消息校验、发布或确认失败时，该条计为 0 并继续处理本批其余消息；
     * 未确认消息保留在数据库中，待租约到期后由后续扫描重新领取。
     * 发布成功但确认失败时可能重复通知，订阅端通过数据库事件序号过滤已处理事件。
     * <p>
     * 整批领取等基础设施错误向调用方传播；成功、失败或取消时均释放 polling 标志。
     *
     * @return 本次成功发布且确认的消息数；无可领消息或已有批次正在处理时为 0，数值不表示模型调用是否完成。
     */
    public Mono<Integer> pollOnce() {
        return Mono.defer(() -> {
            // CAS 只允许当前对象的一个批次进入；重复调用不争抢本批资格，返回 0 等待后续唤醒。
            if (!polling.compareAndSet(false, true)) {
                return Mono.just(0);
            }
            // 仅领取 EVENT，不处理 DISPATCH；存储原子记录 workerId、fencing token 和租约，支持多实例竞争。
            return executionOutboxStore.claim(OutboxMessage.Kind.EVENT, workerId, properties.outboxLease(), BATCH_SIZE)
                    // 将领取列表展开为消息流；concatMap 逐条串行处理，后面的 1 为预取数量。
                    .flatMapMany(Flux::fromIterable)
                    .concatMap(message -> executionOutboxStore.validateClaim(message).flatMap(claim -> {
                        // message 是携带领取身份的工作指针，claim 是校验结果；发布前再次确认该领取尚未失效。
                        if (!claim.successful()) {
                            return Mono.error(AdmissionException.fromStoreRejection(claim.code()));
                        }
                        // 发布流程正常结束后，then 才订阅 ACK 流；确认时存储再次核验 Worker、token 和有效租约。
                        // ack 成功贡献 1，否则转为异常交给本条消息的恢复分支；ACK 不要求当前存在在线浏览器。
                        var timing = new InvocationTiming(message.invocationId(), null, null, System.nanoTime());
                        return executionEventBroadcast.publish(message)
                                .doOnSuccess(ignored -> timing.batch("EVENT_PUBLISHED", timing.startedNanos(), message.eventSequence()))
                                .then(Mono.defer(() -> {
                                    long ackStart = System.nanoTime();
                                    return executionOutboxStore.acknowledge(message).flatMap(ack -> {
                                        if (!ack.successful())
                                            return Mono.error(AdmissionException.fromStoreRejection(ack.code()));
                                        timing.batch("EVENT_ACKED", ackStart, message.eventSequence());
                                        return Mono.just(1);
                                    });
                                }));
                    }).onErrorResume(error -> {
                        // 单条失败不终止整批，也不在这里重新发布；保留未确认记录，租约到期后再领取。
                        log.warn("AI EVENT message {} was not acknowledged", message.messageId());
                        return Mono.just(0);
                    }), 1)
                    // 汇总每条的 1/0；空领取列表也返回 0，确保本批有明确的完成结果。
                    .reduce(0, Integer::sum)
                    // 成功回调先释放 polling，再向下游发送计数；下游满批唤醒才不会被误判为仍在领取。
                    // doFinally 在终止信号传递后执行，可能晚于下游发起的下一次领取，因此这里提前释放。
                    .doOnSuccess(ignored -> polling.set(false))
                    // 整批异常或订阅取消也释放资格，避免后续领取一直被 CAS 拒绝。
                    .doOnError(ignored -> polling.set(false))
                    .doOnCancel(() -> polling.set(false));
        });
    }

    /**
     * 启动当前节点的 EVENT 自动发布循环；Spring 在 isAutoStartup() 为 true 时调用，也可显式手动启动。
     * synchronized 与 running 检查使重复 start 调用不会建立多个循环。
     * <p>
     * 合并事务提交后的内存唤醒与统一恢复定时器，以 LATEST 策略合并积压提示，并串行执行 pollOnce。
     * 初始唤醒让启动后立即检查待发布消息；成功处理满批后再唤醒，持续领取而不必等待下一次恢复定时器。
     * 单批基础设施异常只记录警告并等待后续触发；整个触发流终止时将 running 标为 false。
     */
    @Override
    public synchronized void start() {
        // 生命周期重复调用时复用已经启动的循环，避免重复订阅唤醒通道和定时器。
        if (running) {
            return;
        }
        running = true;
        // wakeups 订阅后先发初始提示，之后接收提交后唤醒；interval 每 5 秒触发一次恢复检查。
        // 两个来源只提供检查时机，待发布记录仍通过 pollOnce 从数据库领取。
        loop = Flux.merge(localExecutionEventNotifier.wakeups(), Flux.interval(RECOVERY_INTERVAL, scheduler))
                // 合并尚未消费的提示，并把后续处理调度到工作线程；publishOn 预取 1，限制调度阶段的待处理提示。
                .onBackpressureLatest().publishOn(scheduler, 1)
                // 串行执行每次检查；ignored 是唤醒值或定时器计数，本流程不依赖其具体数值。
                .concatMap(ignored -> pollOnce().doOnNext(count -> {
                    // count 是成功发布并确认的条数；达到批量上限说明可能仍有待领记录，立即请求下一次检查。
                    if (count == BATCH_SIZE) {
                        localExecutionEventNotifier.wakePublisher();
                    }
                }).onErrorResume(error -> {
                    // 处理整批领取等基础设施错误，保持外层循环存活，后续唤醒或恢复扫描仍可继续检查。
                    log.warn("AI EVENT recovery scan failed");
                    return Mono.just(0);
                }), 1)
                // subscribe 激活冷流程并保存取消句柄；正常计数已在上面处理，未恢复的终止异常更新生命周期状态。
                .subscribe(ignored -> {
                }, error -> running = false);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (loop != null) {
            loop.dispose();
        }
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return properties.workerEnabled();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
