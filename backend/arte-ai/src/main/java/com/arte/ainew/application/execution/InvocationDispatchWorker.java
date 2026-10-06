package com.arte.ainew.application.execution;

import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.config.NewAiExecutionProperties;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.spi.persistence.ExecutionOutboxStore;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.core.enums.ResultCodeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 有界、至少一次的派发消费者
 * <p>
 * 实例标识每次创建均唯一；无 ThreadLocal 用户、无 JVM 权威任务锁。
 * 手动 pollOnce 可用于集成测试；自动循环由显式 workerEnabled 开启，关闭只停止消费，不伪造任务取消终态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 16:14 ✾
 */
public final class InvocationDispatchWorker implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(InvocationDispatchWorker.class);
    private final ExecutionOutboxStore executionOutboxStore;
    private final ExecutionStore executionStore;
    private final InvocationCoordinator invocationCoordinator;
    private final NewAiExecutionProperties newAiExecutionProperties;
    private final Scheduler scheduler;
    private final String workerId = "dispatch-" + UUID.randomUUID();
    private final AtomicBoolean polling = new AtomicBoolean();
    private volatile Disposable loop;
    private volatile boolean running;

    public InvocationDispatchWorker(ExecutionOutboxStore executionOutboxStore, ExecutionStore executionStore,
                                    InvocationCoordinator invocationCoordinator, NewAiExecutionProperties newAiExecutionProperties, Scheduler scheduler) {
        this.executionOutboxStore = executionOutboxStore;
        this.executionStore = executionStore;
        this.invocationCoordinator = invocationCoordinator;
        this.newAiExecutionProperties = newAiExecutionProperties;
        this.scheduler = scheduler;
    }

    /**
     * 每批只领取可立即执行的数量；同实例手动轮询不叠加，跨实例归属仍由数据库仲裁。
     */
    public Mono<Integer> pollOnce() {
        return Mono.defer(() -> {
            if (!polling.compareAndSet(false, true)) {
                return Mono.just(0);
            }
            return executionOutboxStore.claim(OutboxMessage.Kind.DISPATCH, workerId, newAiExecutionProperties.outboxLease(), newAiExecutionProperties.concurrency())
                    .flatMapMany(Flux::fromIterable)
                    .flatMap(message -> process(message).thenReturn(1), newAiExecutionProperties.concurrency(), 1)
                    .reduce(0, Integer::sum)
                    .doFinally(ignored -> polling.set(false));
        });
    }

    private Mono<Void> process(OutboxMessage message) {
        return executionStore.find(message.owner(), message.invocationId())
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                .flatMap(invocation -> {
                    // 这里只读取可信记录；派发边界重新授权，过期／撤销也由协调器耐久结束。
                    var original = invocation.request().context();
                    var deadline = invocation.request().options().deadline();
                    var effective = new com.arte.ainew.common.execution.ExecutionContext(original.executionId(), original.traceId(),
                            original.authorization(), deadline, original.parentExecutionId(), original.budgetRef(),
                            original.releaseRef(), original.idempotencyKey());
                    var runtime = ExecutionRuntimeContext.start(effective);
                    var work = invocationCoordinator.dispatch(message, runtime);
                    var heartbeat = Flux.interval(newAiExecutionProperties.outboxLease().dividedBy(3), scheduler)
                            .concatMap(ignored -> executionOutboxStore.renewClaim(message, newAiExecutionProperties.outboxLease()).flatMap(value ->
                                    value.successful() ? Mono.empty() : Mono.error(AdmissionException.fromStoreRejection(value.code()))), 1)
                            .then();
                    return Mono.firstWithSignal(work, heartbeat)
                            .doOnCancel(() -> runtime.cancellation().cancel("WORKER_STOPPED_OR_LEASE_LOST"))
                            .doOnError(error -> runtime.cancellation().cancel("WORKER_FAILURE"))
                            .then(executionOutboxStore.acknowledge(message).flatMap(value -> value.successful() ? Mono.empty()
                                    : Mono.error(AdmissionException.fromStoreRejection(value.code())))).then();
                })
                .onErrorResume(error -> {
                    // 不 ACK，留待租约到期重领。只记录稳定错误分类，避免异常正文携带 SQL／凭据／用户输入。
                    String code = error instanceof AdmissionException rejected ? rejected.getResultCode().name() : "WORKER_INFRASTRUCTURE_FAILURE";
                    LOG.warn("AI dispatch message {} was not acknowledged: {}", message.messageId(), code);
                    return Mono.empty();
                });
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        loop = Mono.defer(() -> pollOnce().onErrorResume(error -> {
                    LOG.warn("AI outbox polling failed: WORKER_INFRASTRUCTURE_FAILURE");
                    return Mono.just(0);
                })).then(Mono.delay(newAiExecutionProperties.pollInterval(), scheduler))
                .repeat().subscribe(ignored -> {
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
        return newAiExecutionProperties.workerEnabled();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
