package com.arte.ainew.application.execution;

import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.config.NewAiExecutionProperties;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.spi.persistence.ExecutionOutboxStore;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.core.enums.ResultCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.Disposables;
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
@Slf4j
public final class InvocationDispatchWorker implements SmartLifecycle {

    private final ExecutionOutboxStore executionOutboxStore;
    private final ExecutionStore executionStore;
    private final InvocationCoordinator invocationCoordinator;
    private final NewAiExecutionProperties newAiExecutionProperties;
    private final Scheduler scheduler;
    private final String workerId = "dispatch-" + UUID.randomUUID();
    private final AtomicBoolean polling = new AtomicBoolean();
    private final LocalDispatchNotifier notifier;
    private volatile Run current;
    private volatile boolean running;

    public InvocationDispatchWorker(ExecutionOutboxStore executionOutboxStore, ExecutionStore executionStore,
                                    InvocationCoordinator invocationCoordinator, NewAiExecutionProperties newAiExecutionProperties, Scheduler scheduler) {
        this(executionOutboxStore, executionStore, invocationCoordinator, newAiExecutionProperties, scheduler, new LocalDispatchNotifier());
    }

    public InvocationDispatchWorker(ExecutionOutboxStore executionOutboxStore, ExecutionStore executionStore,
                                    InvocationCoordinator invocationCoordinator, NewAiExecutionProperties newAiExecutionProperties,
                                    Scheduler scheduler, LocalDispatchNotifier notifier) {
        this.notifier = notifier;
        this.executionOutboxStore = executionOutboxStore;
        this.executionStore = executionStore;
        this.invocationCoordinator = invocationCoordinator;
        this.newAiExecutionProperties = newAiExecutionProperties;
        this.scheduler = scheduler;
    }

    /**
     * 手动处理一批，只领取可立即执行的数量；自动运行或已有手动批次时返回 0。
     * 自动运行按槽位持续补充，手动批次保留完成后返回计数的兼容语义；跨实例归属仍由数据库仲裁。
     */
    public Mono<Integer> pollOnce() {
        return Mono.defer(() -> {
            synchronized (this) {
                if (running || !polling.compareAndSet(false, true)) return Mono.just(0);
            }
            return executionOutboxStore.claim(OutboxMessage.Kind.DISPATCH, workerId, newAiExecutionProperties.outboxLease(), newAiExecutionProperties.concurrency())
                    .doOnNext(messages -> {
                        if (!messages.isEmpty())
                            log.debug("AI dispatch batch claimed, workerId={}, count={}", workerId, messages.size());
                    })
                    .flatMapMany(Flux::fromIterable)
                    .flatMap(message -> process(message).thenReturn(1), newAiExecutionProperties.concurrency(), 1)
                    .reduce(0, Integer::sum)
                    .doFinally(ignored -> {
                        polling.set(false);
                        if (running) notifier.wakeWorker();
                    });
        });
    }

    private Mono<Void> process(OutboxMessage message) {
        return executionStore.find(message.owner(), message.invocationId())
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                .flatMap(invocation -> {
                    log.info("AI dispatch message processing, invocationId={}, traceId={}, messageId={}, workerId={}, fencingToken={}, state={}",
                            message.invocationId(), invocation.request().context().traceId(), message.messageId(), workerId, message.fencingToken(), invocation.state());
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
                .onErrorResume(DispatchDeferredException.class, deferred ->
                        executionOutboxStore.defer(message, deferred.delay()).flatMap(value -> value.successful() ? Mono.empty()
                                : Mono.error(AdmissionException.fromStoreRejection(value.code()))).then())
                .onErrorResume(error -> {
                    // 不 ACK，留待租约到期重领。只记录稳定错误分类，避免异常正文携带 SQL／凭据／用户输入。
                    String code = error instanceof AdmissionException rejected ? rejected.getResultCode().name() : "WORKER_INFRASTRUCTURE_FAILURE";
                    log.warn("AI dispatch message not acknowledged; reclaim after lease expiry, invocationId={}, messageId={}, workerId={}, code={}, type={}",
                            message.invocationId(), message.messageId(), workerId, code, error.getClass().getName());
                    return Mono.empty();
                });
    }

    /**
     * 自动运行按空闲槽位领取；提交、完成和统一恢复定时器均只发检查提示。
     */
    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        var run = new Run();
        current = run;
        log.info("AI dispatch worker started, workerId={}, concurrency={}, recoveryInterval={}, outboxLease={}",
                workerId, newAiExecutionProperties.concurrency(), newAiExecutionProperties.pollInterval(), newAiExecutionProperties.outboxLease());
        run.loop = Flux.merge(notifier.wakeups(), Flux.interval(newAiExecutionProperties.pollInterval(), scheduler))
                .onBackpressureLatest().publishOn(scheduler, 1)
                // 只串行领取，任务独立订阅；长任务不会阻塞下一次补充槽位。
                .concatMap(ignored -> run.refill(), 1)
                .subscribe(ignored -> {
                }, error -> {
                    synchronized (this) {
                        if (current == run) {
                            running = false;
                            run.tasks.dispose();
                        }
                    }
                    log.error("AI dispatch worker loop stopped unexpectedly, workerId={}, type={}", workerId, error.getClass().getName());
                });
    }

    /**
     * 单次启动拥有独立的计数与订阅，旧启动的完成回调不得影响重启后的槽位。
     */
    private final class Run {
        private final Disposable.Composite tasks = Disposables.composite();
        private Disposable loop;
        // 计数变更及启动/停止与领取结果交接由外部对象监视器保护。
        private int active;

        private boolean live() {
            return running && current == this;
        }

        private Mono<Integer> refill() {
            return Mono.defer(() -> {
                int free;
                synchronized (InvocationDispatchWorker.this) {
                    if (!live() || polling.get()) return Mono.just(0);
                    free = newAiExecutionProperties.concurrency() - active;
                }
                if (free == 0) return Mono.just(0);
                long started = System.nanoTime();
                return executionOutboxStore.claim(OutboxMessage.Kind.DISPATCH, workerId,
                                newAiExecutionProperties.outboxLease(), free)
                        .map(messages -> {
                            synchronized (InvocationDispatchWorker.this) {
                                // 停止后才完成的领取不启动工作，未确认记录由租约恢复。
                                if (!live()) return 0;
                                for (var message : messages) launch(message);
                                if (!messages.isEmpty())
                                    log.debug("AI dispatch slots filled, workerId={}, claimed={}, active={}, claimMs={}",
                                            workerId, messages.size(), active, (System.nanoTime() - started) / 1_000_000);
                                return messages.size();
                            }
                        });
            }).onErrorResume(error -> {
                log.warn("AI dispatch outbox recovery scan failed, workerId={}, type={}", workerId, error.getClass().getName());
                return Mono.just(0);
            });
        }

        private void launch(OutboxMessage message) {
            active++;
            // 先登记占位句柄，兼容同步完成及 stop 与 subscribe 交错；已完成句柄及时移除。
            Disposable.Swap slot = Disposables.swap();
            tasks.add(slot);
            slot.update(Mono.defer(() -> process(message)).doFinally(ignored -> {
                synchronized (InvocationDispatchWorker.this) {
                    tasks.remove(slot);
                    slot.dispose();
                    active--;
                    if (live()) notifier.wakeWorker();
                }
            }).subscribe());
        }
    }

    @Override
    public synchronized void stop() {
        if (running) log.info("AI dispatch worker stopping, workerId={}", workerId);
        running = false;
        var run = current;
        current = null;
        if (run != null) {
            if (run.loop != null) run.loop.dispose();
            run.tasks.dispose();
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
