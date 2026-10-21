package com.arte.ainew.application.execution;

import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.support.ToolObservation;
import com.arte.ainew.config.NewAiExecutionProperties;
import com.arte.ainew.config.NewAiSchedulingProperties;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.spi.persistence.DispatchSchedulingStore;
import com.arte.ainew.spi.persistence.ExecutionOutboxStore;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.core.diagnostics.OperationObservation;
import com.arte.core.diagnostics.RequestObservation;
import com.arte.core.diagnostics.SafeExceptionDiagnostics;
import com.arte.core.enums.ResultCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

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
    private final NewAiSchedulingProperties scheduling;
    private final Supplier<String> nodeManifest;
    private final AtomicBoolean toolReady = new AtomicBoolean();
    private final AtomicBoolean poolReady = new AtomicBoolean();
    private volatile Run current;
    private volatile boolean running;

    public InvocationDispatchWorker(ExecutionOutboxStore executionOutboxStore, ExecutionStore executionStore,
                                    InvocationCoordinator invocationCoordinator, NewAiExecutionProperties newAiExecutionProperties, Scheduler scheduler) {
        this(executionOutboxStore, executionStore, invocationCoordinator, newAiExecutionProperties, scheduler, new LocalDispatchNotifier());
    }

    public InvocationDispatchWorker(ExecutionOutboxStore executionOutboxStore, ExecutionStore executionStore,
                                    InvocationCoordinator invocationCoordinator, NewAiExecutionProperties newAiExecutionProperties,
                                    Scheduler scheduler, LocalDispatchNotifier notifier) {
        this(executionOutboxStore, executionStore, invocationCoordinator, newAiExecutionProperties, scheduler, notifier,
                NewAiSchedulingProperties.disabled(), () -> null);
    }

    public InvocationDispatchWorker(ExecutionOutboxStore executionOutboxStore, ExecutionStore executionStore,
                                    InvocationCoordinator invocationCoordinator, NewAiExecutionProperties newAiExecutionProperties, Scheduler scheduler,
                                    LocalDispatchNotifier notifier, NewAiSchedulingProperties scheduling, Supplier<String> nodeManifest) {
        this.scheduling = scheduling;
        this.nodeManifest = nodeManifest;
        if (scheduling.enabled() && (scheduling.slots().total() != newAiExecutionProperties.concurrency()
                || !(executionOutboxStore instanceof DispatchSchedulingStore)))
            throw new IllegalArgumentException("Scheduling requires a classified store and slots.total == concurrency");
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
            var released = new AtomicBoolean();
            Runnable release = () -> {
                if (released.compareAndSet(false, true)) {
                    polling.set(false);
                    if (running) notifier.wakeWorker();
                }
            };
            return Mono.defer(this::claimBatch)
                    .doOnNext(messages -> {
                        if (!messages.isEmpty())
                            log.debug("AI dispatch batch claimed, workerId={}, count={}", workerId, messages.size());
                    })
                    .flatMapMany(Flux::fromIterable)
                    .flatMap(message -> process(message).thenReturn(1), newAiExecutionProperties.concurrency(), 1)
                    .reduce(0, Integer::sum)
                    // 在回执发出前释放本批标记，避免紧接着的下一次手动领取被误报为仍在运行。
                    .doOnSuccess(ignored -> release.run())
                    .doOnError(ignored -> release.run())
                    .doOnCancel(release);
        });
    }

    private Mono<List<OutboxMessage>> claimBatch() {
        if (!scheduling.enabled())
            return executionOutboxStore.claim(OutboxMessage.Kind.DISPATCH, workerId, newAiExecutionProperties.outboxLease(), newAiExecutionProperties.concurrency());
        return ((DispatchSchedulingStore) executionOutboxStore).expireQueued(64)
                .thenMany(Flux.fromArray(OutboxMessage.WorkClass.values()))
                .concatMap(type -> claimType(type, scheduling.slots().capacity(type))).flatMapIterable(value -> value).collectList();
    }

    /**
     * 节点清单变化后立即退出工具领取；不影响生成保留槽，下一次健康刷新可以重新加入同构池。
     */
    public boolean toolExecutionReady() {
        return scheduling.enabled() && poolReady.get() && scheduling.toolManifest() != null && scheduling.toolManifest().equals(nodeManifest.get());
    }

    private Mono<List<OutboxMessage>> claimType(OutboxMessage.WorkClass type, int free) {
        if (free == 0 || type == OutboxMessage.WorkClass.OUTCOME_CHECK) return Mono.just(List.of());
        boolean tool = type == OutboxMessage.WorkClass.TOOL_SHORT || type == OutboxMessage.WorkClass.TOOL_LONG;
        var manifest = nodeManifest.get();
        if (tool) {
            boolean ready = scheduling.toolManifest() != null && scheduling.toolManifest().equals(manifest);
            if (!ready) {
                updateToolReadiness(false);
                return Mono.just(List.of());
            }
        }
        return ((DispatchSchedulingStore) executionOutboxStore).claimClass(type, workerId, newAiExecutionProperties.outboxLease(), free, manifest)
                .doOnNext(ignored -> {
                    poolReady.set(true);
                    if (tool) updateToolReadiness(true);
                }).doOnError(ignored -> {
                    poolReady.set(false);
                    updateToolReadiness(false);
                });
    }

    private void updateToolReadiness(boolean ready) {
        if (toolReady.getAndSet(ready) != ready)
            log.warn("AI tool dispatch readiness changed, workerId={}, ready={}", workerId, ready);
    }

    private Mono<Void> process(OutboxMessage message) {
        var traceId = new java.util.concurrent.atomic.AtomicReference<String>();
        boolean toolWork = message.workClass() == OutboxMessage.WorkClass.TOOL_SHORT || message.workClass() == OutboxMessage.WorkClass.TOOL_LONG;
        Mono<Invocation> loading = toolWork
                ? OperationObservation.observe(ToolObservation.Stage.WORKER_LOAD.name(), message.invocationId(), null, null,
                span -> executionStore.find(message.owner(), message.invocationId()).doOnNext(invocation ->
                        span.correlate(invocation.request().context().traceId(), invocation.request().options().deadline())))
                : executionStore.find(message.owner(), message.invocationId());
        return RequestObservation.background(loading
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                .flatMap(invocation -> {
                    traceId.set(invocation.request().context().traceId());
                    log.info("AI dispatch message processing, invocationId={}, traceId={}, messageId={}, workerId={}, fencingToken={}, state={}",
                            message.invocationId(), invocation.request().context().traceId(), message.messageId(), workerId, message.fencingToken(), invocation.state());
                    // 这里只读取可信记录；派发边界重新授权，过期／撤销也由协调器耐久结束。
                    var original = invocation.request().context();
                    var deadline = invocation.request().options().deadline();
                    var effective = new com.arte.ainew.common.execution.ExecutionContext(original.executionId(), original.traceId(),
                            original.authorization(), deadline, original.parentExecutionId(), original.budgetRef(),
                            original.releaseRef(), original.idempotencyKey());
                    var runtime = ExecutionRuntimeContext.start(effective);
                    if (toolWork) ToolObservation.point(ToolObservation.Stage.WORKER_RECORD_LOADED, effective);
                    boolean tool = message.workClass() == OutboxMessage.WorkClass.TOOL_SHORT || message.workClass() == OutboxMessage.WorkClass.TOOL_LONG;
                    var work = scheduling.enabled() && tool && !toolExecutionReady()
                            ? Mono.<Void>error(new DispatchDeferredException(java.time.Duration.ofSeconds(1)))
                            : invocationCoordinator.dispatch(message, runtime);
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
                    // 不 ACK，留待租约到期重领；非预期故障保留脱敏原因链，不改变恢复语义。
                    String code = error instanceof AdmissionException rejected ? rejected.getResultCode().name() : "WORKER_INFRASTRUCTURE_FAILURE";
                    if (error instanceof AdmissionException || error instanceof org.springframework.security.access.AccessDeniedException) {
                        log.warn("AI dispatch message not acknowledged; reclaim after lease expiry, invocationId={}, traceId={}, messageId={}, workerId={}, code={}, type={}",
                                message.invocationId(), traceId.get(), message.messageId(), workerId, code, error.getClass().getName());
                    } else {
                        log.error("AI dispatch message not acknowledged; reclaim after lease expiry, invocationId={}, traceId={}, messageId={}, workerId={}, code={}, type={}",
                                message.invocationId(), traceId.get(), message.messageId(), workerId, code, error.getClass().getName(),
                                SafeExceptionDiagnostics.forLogging(error));
                    }
                    return Mono.empty();
                }));
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
                    log.error("AI dispatch worker loop stopped unexpectedly, workerId={}, type={}", workerId, error.getClass().getName(), SafeExceptionDiagnostics.forLogging(error));
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
        private final EnumMap<OutboxMessage.WorkClass, Integer> byClass = new EnumMap<>(OutboxMessage.WorkClass.class);

        private boolean live() {
            return running && current == this;
        }

        private Mono<Integer> refill() {
            if (scheduling.enabled()) return RequestObservation.background(refillClasses());
            return RequestObservation.background(Mono.defer(() -> {
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
                log.error("AI dispatch outbox recovery scan failed, workerId={}, type={}", workerId, error.getClass().getName(), SafeExceptionDiagnostics.forLogging(error));
                return Mono.just(0);
            }));
        }

        /**
         * 每类按自己的空闲槽位领取并立即启动；长任务不会占用其他类别的保留槽。
         */
        private Mono<Integer> refillClasses() {
            return Mono.defer(() -> {
                synchronized (InvocationDispatchWorker.this) {
                    if (!live() || polling.get()) return Mono.just(0);
                }
                return ((DispatchSchedulingStore) executionOutboxStore).expireQueued(64)
                        .thenMany(Flux.fromArray(OutboxMessage.WorkClass.values())).concatMap(type -> {
                            int free;
                            synchronized (InvocationDispatchWorker.this) {
                                if (!live()) return Mono.just(0);
                                free = scheduling.slots().capacity(type) - byClass.getOrDefault(type, 0);
                            }
                            return claimType(type, free).map(messages -> {
                                synchronized (InvocationDispatchWorker.this) {
                                    if (!live()) return 0;
                                    messages.forEach(this::launch);
                                    return messages.size();
                                }
                            });
                        }, 1).reduce(0, Integer::sum);
            }).onErrorResume(error -> {
                log.error("AI classified dispatch scan failed, workerId={}, type={}", workerId, error.getClass().getName(), SafeExceptionDiagnostics.forLogging(error));
                return Mono.just(0);
            });
        }

        private void launch(OutboxMessage message) {
            active++;
            byClass.merge(message.workClass(), 1, Integer::sum);
            // 先登记占位句柄，兼容同步完成及 stop 与 subscribe 交错；已完成句柄及时移除。
            Disposable.Swap slot = Disposables.swap();
            tasks.add(slot);
            slot.update(Mono.defer(() -> process(message)).doFinally(ignored -> {
                synchronized (InvocationDispatchWorker.this) {
                    tasks.remove(slot);
                    slot.dispose();
                    active--;
                    byClass.merge(message.workClass(), -1, Integer::sum);
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
