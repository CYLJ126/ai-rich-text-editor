package com.arte.ainew.application.execution;

import com.arte.ainew.api.execution.BudgetService;
import com.arte.ainew.api.execution.ExecutionEventService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionEvent;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.LiveTextDelta;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationBudgetState;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.spi.conversation.ConversationContentVisibility;
import com.arte.ainew.spi.persistence.ExecutionEventStore;
import com.arte.ainew.spi.persistence.ExecutionResultStore;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.core.enums.ResultCodeEnum;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 已提交结果读取、事件分页重放以及当前节点的实时事件订阅服务。
 * <p>
 * 读取前重新检查权限及调用归属；实时通知只触发数据库重放，本地与 Redis 唤醒共用同一读取流程。
 * 只沿 Invocation 已提交的结果引用读取结果，不暴露尚未关联到调用的孤立结果，也不重新执行模型调用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 16:14 ✾
 */
@Slf4j
public final class DefaultExecutionEventService implements ExecutionEventService {
    /**
     * 当前权限检查入口；单次读取、订阅建连、每次唤醒及长连接定时检查均重新校验 READ 权限与期限。
     */
    private final AdmissionAuthorization authorization;

    /**
     * 已提交的调用状态存储，用于确认归属、读取结果引用及判断调用是否进入终态。
     */
    private final ExecutionStore executionStore;

    /**
     * 已提交事件及保留边界的存储，按调用内排他游标读取有界页面，不依赖通知携带业务内容。
     */
    private final ExecutionEventStore executionEventStore;

    /**
     * 结果内容存储，沿可信 ResultRef 检查归属、类型、版本及摘要后读取。
     */
    private final ExecutionResultStore executionResultStore;

    /**
     * 本节点的唤醒通道，可接收本地发布或 Redis 广播后的提示；为 null 时仅支持结果读取和分页重放。
     */
    private final LocalExecutionEventNotifier localExecutionEventNotifier;

    /**
     * 计算本次订阅上下文的剩余期限；可注入时钟，不改变原模型调用的执行期限。
     */
    private final Clock clock;

    /**
     * 当前 Attempt 的预算状态查询器；RESERVED 时继续等待，兼容装配为 null 时不等待预算。
     */
    private final InvocationBudgetStatusResolver budgetStatus;

    private final LiveTextNotifier liveTextNotifier;

    private final ConversationContentVisibility contentVisibility;

    /**
     * 实时订阅每次重放的最大事件数；满页连续读取下一页，与通知次数或 SSE 帧大小无关。
     */
    private static final int WATCH_PAGE_SIZE = 64;

    /**
     * 长连接重新授权的间隔；此定时任务不读取 Invocation/Event 表，也不是调用状态轮询。
     */
    public static final Duration REAUTHORIZE_INTERVAL = Duration.ofSeconds(10);

    /**
     * 兼容只读取结果／重放的独立装配；实时订阅必须显式共享同一个通知器。
     */
    public DefaultExecutionEventService(AdmissionAuthorization authorization, ExecutionStore executionStore,
                                        ExecutionEventStore executionEventStore, ExecutionResultStore executionResultStore) {
        this(authorization, executionStore, executionEventStore, executionResultStore, null, Clock.systemUTC());
    }

    public DefaultExecutionEventService(AdmissionAuthorization authorization, ExecutionStore executionStore,
                                        ExecutionEventStore executionEventStore, ExecutionResultStore executionResultStore,
                                        LocalExecutionEventNotifier localExecutionEventNotifier, Clock clock) {
        this(authorization, executionStore, executionEventStore, executionResultStore, localExecutionEventNotifier, clock,
                executionStore instanceof BudgetService budgets ? new InvocationBudgetStatusResolver(executionStore, budgets) : null);
    }

    public DefaultExecutionEventService(AdmissionAuthorization authorization, ExecutionStore executionStore,
                                        ExecutionEventStore executionEventStore, ExecutionResultStore executionResultStore,
                                        LocalExecutionEventNotifier localExecutionEventNotifier, Clock clock, InvocationBudgetStatusResolver budgetStatus) {
        this(authorization, executionStore, executionEventStore, executionResultStore, localExecutionEventNotifier, clock, budgetStatus, null);
    }

    public DefaultExecutionEventService(AdmissionAuthorization authorization, ExecutionStore executionStore,
                                        ExecutionEventStore executionEventStore, ExecutionResultStore executionResultStore,
                                        LocalExecutionEventNotifier localExecutionEventNotifier, Clock clock, InvocationBudgetStatusResolver budgetStatus,
                                        LiveTextNotifier liveTextNotifier) {
        this(authorization, executionStore, executionEventStore, executionResultStore, localExecutionEventNotifier, clock, budgetStatus, liveTextNotifier, null);
    }

    public DefaultExecutionEventService(AdmissionAuthorization authorization, ExecutionStore executionStore, ExecutionEventStore executionEventStore,
                                        ExecutionResultStore executionResultStore, LocalExecutionEventNotifier localExecutionEventNotifier, Clock clock,
                                        InvocationBudgetStatusResolver budgetStatus, LiveTextNotifier liveTextNotifier,
                                        com.arte.ainew.spi.conversation.ConversationContentVisibility contentVisibility) {
        this.contentVisibility = contentVisibility;
        this.liveTextNotifier = liveTextNotifier;
        this.budgetStatus = budgetStatus;
        this.localExecutionEventNotifier = localExecutionEventNotifier;
        this.clock = clock;
        this.authorization = authorization;
        this.executionStore = executionStore;
        this.executionEventStore = executionEventStore;
        this.executionResultStore = executionResultStore;
    }

    @Override
    public Mono<List<InvocationSnapshot>> snapshots(List<String> ids, ExecutionContext context) {
        return snapshots(ids, Map.of(), context);
    }

    @Override
    public Mono<List<InvocationSnapshot>> snapshots(List<String> ids, Map<String, Long> knownResultVersions, ExecutionContext context) {
        com.arte.ainew.common.validation.ContractChecks.require(!ids.isEmpty() && ids.size() <= 20, "Batch requires 1 to 20 IDs");
        var distinct = ids.stream().distinct().toList();
        return authorization.require(context, AdmissionAuthorization.READ).flatMap(current -> {
            var owner = ExecutionOwner.from(current);
            Mono<List<com.arte.ainew.spi.persistence.ExecutionReadStore.Snapshot>> snapshots;
            if (executionStore instanceof com.arte.ainew.spi.persistence.ExecutionReadStore batch)
                snapshots = batch.snapshots(owner, distinct);
            else
                snapshots = Flux.fromIterable(distinct).concatMap(id -> executionStore.find(owner, id).flatMap(invocation ->
                        (budgetStatus == null ? Mono.just(InvocationBudgetState.NOT_RESERVED) : budgetStatus.resolve(invocation))
                                .map(state -> new com.arte.ainew.spi.persistence.ExecutionReadStore.Snapshot(invocation, state)))).collectList();
            return snapshots.flatMap(values -> {
                var all = new HashMap<String, Invocation>();
                values.forEach(value -> all.put(value.invocation().request().context().executionId(), value.invocation()));
                return readable(owner, all, current).flatMap(allowed -> {
                    var byId = new HashMap<String, com.arte.ainew.spi.persistence.ExecutionReadStore.Snapshot>();
                    var references = new HashMap<String, com.arte.ainew.pojo.execution.ResultRef>();
                    values.stream().filter(value -> allowed.contains(value.invocation().request().context().executionId())).forEach(value -> {
                        var id = value.invocation().request().context().executionId();
                        byId.put(id, value);
                        if (value.invocation().result() != null && value.invocation().state().terminal()
                                && !Objects.equals(knownResultVersions.get(id), value.invocation().version()))
                            references.put(id, value.invocation().result());
                    });
                    return executionResultStore.findAll(owner, references).flatMap(results -> readable(owner, all, current).map(confirmed -> distinct.stream().map(id -> {
                        var value = byId.get(id);
                        if (value == null || !confirmed.contains(id))
                            return new InvocationSnapshot(id, null, null, null);
                        if (references.containsKey(id) && !results.containsKey(id))
                            throw new IllegalStateException("Committed result bytes are missing");
                        return new InvocationSnapshot(id, value.invocation(), value.budgetState(), results.get(id));
                    }).toList()));
                });
            });
        });
    }

    private Mono<Set<String>> readable(ExecutionOwner owner, Map<String, Invocation> invocations, ExecutionContext context) {
        return Flux.fromIterable(invocations.entrySet()).concatMap(entry -> authorization.readInvocation(entry.getValue(), context)
                        .thenReturn(entry.getKey()).onErrorResume(org.springframework.security.access.AccessDeniedException.class, denied -> Mono.empty()))
                .collect(java.util.stream.Collectors.toSet()).flatMap(tools -> {
                    if (contentVisibility == null) return Mono.just(Set.copyOf(tools));
                    return contentVisibility.readableInvocations(owner, Map.copyOf(invocations)).map(visible -> {
                        tools.retainAll(visible);
                        return Set.copyOf(tools);
                    });
                });
    }

    /**
     * 每次事件授权同时复核 TOOL 当前资格，包含无通知期间的定时撤权检查。
     */
    private Mono<ExecutionContext> authorizeRead(String id, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.READ).flatMap(current ->
                executionStore.find(ExecutionOwner.from(current), id)
                        .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                        .flatMap(invocation -> authorization.readInvocation(invocation, current)).thenReturn(current));
    }

    @Override
    public Mono<WatchPreparation> prepareWatch(ExecutionEvent.Cursor cursor, ExecutionContext context) {
        return authorizeRead(cursor.executionId(), context).flatMap(current -> {
            var checkedAt = clock.instant();
            var owner = ExecutionOwner.from(current);
            return executionEventStore.replay(owner, cursor, 1)
                    .map(validation -> new WatchPreparation(validation,
                            combined(cursor, context, current, checkedAt)));
        });
    }

    /**
     * 一条连接共享初始授权、归属检查和定时撤权检查；并发唤醒只合并正在进行的授权。
     */
    @Override
    public Flux<WatchSignal> watchCombined(ExecutionEvent.Cursor cursor, ExecutionContext context) {
        return combined(cursor, context, null, null);
    }

    private Flux<WatchSignal> combined(ExecutionEvent.Cursor cursor, ExecutionContext context,
                                       ExecutionContext prepared, java.time.Instant checkedAt) {
        if (localExecutionEventNotifier == null)
            return Flux.error(new AdmissionException(ResultCodeEnum.AI_EVENT_WATCH_NOT_ENABLED));
        var subscribed = new AtomicBoolean();
        return Flux.defer(() -> {
            if (prepared != null && !subscribed.compareAndSet(false, true))
                return Flux.error(new IllegalStateException("Prepared watch already subscribed"));
            var gate = new ConnectionAuthorization(cursor.executionId(), context);
            var done = Sinks.<Void>one();
            var after = new AtomicLong(cursor.afterSequence());
            var outcomeDelivered = new AtomicBoolean();
            var remaining = Duration.between(clock.instant(), context.deadline());
            if (remaining.isNegative() || remaining.isZero()) return gate.check().thenMany(Flux.empty());
            // 仅 HTTP 本次准备到实际订阅的一秒内交接可复用；延迟订阅重新检查，不能跨请求缓存。
            boolean reuse = prepared != null && !clock.instant().isAfter(checkedAt.plusSeconds(1));
            return (reuse ? Mono.just(prepared) : gate.check()).flatMapMany(current -> {
                var owner = ExecutionOwner.from(current);
                return (reuse ? Mono.just(true) : executionStore.find(owner, cursor.executionId())
                        .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                        .thenReturn(true))
                        .flatMapMany(ignored -> {
                            var events = localExecutionEventNotifier.watch(owner, cursor.executionId())
                                    .publishOn(Schedulers.parallel(), 1)
                                    .filter(hint -> hint == 0L || hint > after.get())
                                    .concatMap(hint -> (hint == 0L ? Mono.just(current) : gate.check())
                                            .thenMany(drain(owner, cursor.executionId(), after, done, outcomeDelivered, false)), 1)
                                    .map(event -> (WatchSignal) new WatchSignal.Event(event))
                                    .takeUntilOther(done.asMono());
                            Flux<WatchSignal> text = liveTextNotifier == null ? Flux.empty()
                                    : liveTextNotifier.watch(owner, cursor.executionId()).publishOn(Schedulers.parallel(), 1)
                                    .map(delta -> (WatchSignal) new WatchSignal.Text(delta)).takeUntilOther(done.asMono());
                            var firstCheck = reuse ? Duration.between(clock.instant(), checkedAt.plus(REAUTHORIZE_INTERVAL)) : REAUTHORIZE_INTERVAL;
                            if (firstCheck.isNegative()) firstCheck = Duration.ZERO;
                            var reauthorize = Flux.interval(firstCheck, REAUTHORIZE_INTERVAL).concatMap(tick -> gate.check(), 1)
                                    .thenMany(Flux.<WatchSignal>never()).takeUntilOther(done.asMono());
                            return Flux.merge(1, events, text, reauthorize);
                        });
            }).takeUntilOther(Mono.delay(remaining));
        });
    }

    private final class ConnectionAuthorization {
        private final ExecutionContext context;
        private final String invocationId;
        private Mono<ExecutionContext> pending;
        // 仅缓存同一连接内不可变的能力／归属；当前权限仍每次重新检查，不缓存 Permit。
        private Invocation target;

        private ConnectionAuthorization(String invocationId, ExecutionContext context) {
            this.invocationId = invocationId;
            this.context = context;
        }

        private synchronized Mono<ExecutionContext> check() {
            if (pending != null) return pending;
            pending = authorization.require(context, AdmissionAuthorization.READ).flatMap(current -> {
                Mono<Invocation> found = target == null ? executionStore.find(ExecutionOwner.from(current), invocationId)
                        .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND))).doOnNext(value -> target = value)
                        : Mono.just(target);
                return found.flatMap(value -> authorization.readInvocation(value, current)).thenReturn(current);
            }).doFinally(ignored -> clear()).cache();
            return pending;
        }

        private synchronized void clear() {
            pending = null;
        }
    }

    @Override
    public Flux<LiveTextDelta> watchText(String invocationId, ExecutionContext context) {
        if (liveTextNotifier == null) return Flux.empty();
        var preview = Flux.defer(() -> authorizeRead(invocationId, context).flatMapMany(current -> {
            var owner = ExecutionOwner.from(current);
            return executionStore.find(owner, invocationId)
                    .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                    .flatMapMany(ignored -> liveTextNotifier.watch(owner, invocationId).publishOn(Schedulers.parallel(), 1));
        }));
        var reauthorize = Flux.interval(REAUTHORIZE_INTERVAL)
                .concatMap(ignored -> authorizeRead(invocationId, context), 1)
                .thenMany(Flux.<LiveTextDelta>never());
        return Flux.merge(1, preview, reauthorize).takeUntilOther(Mono.delay(Duration.between(clock.instant(), context.deadline()).isNegative()
                        ? Duration.ZERO : Duration.between(clock.instant(), context.deadline())))
                .doOnError(error -> log.warn("AI text preview subscription failed, invocationId={}, traceId={}, type={}",
                        invocationId, context.traceId(), error.getClass().getName()));
    }

    /**
     * 检查当前 READ 权限及调用归属，从排他游标读取一页已提交事件。
     * <p>
     * 先按当前租户、工作空间和主体查找 Invocation，找不到时以 AI_NOT_FOUND 异常结束；
     * 找到后委托事件存储检查游标保留边界并分页读取，不自动读取后续页面，也不触发模型调用。
     * 存储返回的 CURSOR_EXPIRED 等拒绝结果原样保留在 StoreOutcome 中，由调用方决定如何恢复或映射 HTTP 错误。
     *
     * @param cursor  目标调用及已读取事件序号，仅读取大于 afterSequence 的事件
     * @param limit   单页事件数上限，具体允许范围由事件存储校验
     * @param context 本次读取的身份、权限及期限上下文
     * @return 包含事件、下一页游标及裁剪边界的读取结果；权限、期限或调用不存在等错误通过 Mono 传播
     */
    @Override
    public Mono<StoreOutcome<ExecutionEventStore.Page>> replay(ExecutionEvent.Cursor cursor, int limit, ExecutionContext context) {
        // 权限通过后再按完整归属定位调用，不能仅凭客户端提供的 executionId 读取事件。
        return authorizeRead(cursor.executionId(), context).flatMap(current ->
                        executionEventStore.replay(ExecutionOwner.from(current), cursor, limit))
                .doOnNext(outcome -> {
                    if (!outcome.successful())
                        log.warn("AI event replay rejected, invocationId={}, traceId={}, afterSequence={}, code={}",
                                cursor.executionId(), context.traceId(), cursor.afterSequence(), outcome.code());
                });
    }

    /**
     * 从给定游标补读历史，随后通过唤醒提示继续读取新提交的事件。
     * <p>
     * 每次订阅拥有独立游标和结束信号；先验证权限及归属，再登记通知监听并接收初始 0L 提示，
     * 避免先读取历史、后登记监听的空窗。提示只触发重放，不能直接更新读取游标；重复事件按 sequence 过滤。
     * 重放串行执行，通知和调度缓冲有界；同时每 10 秒重新检查权限，未收到通知时不定时读取调用或事件表。
     * <p>
     * 调用终态与预算处理结果均满足结束条件后，补读最后一批事件再结束；仍为 RESERVED 时等待预算事件。
     * UNKNOWN 且本次订阅尚未输出 TERMINAL/BUDGET_CHANGED 时保留订阅，允许等待后续核对事件。
     * 剩余订阅期限到达或客户端取消时停止读取并注销监听，不取消模型调用，也不延长模型执行期限。
     * 通知器未配置、授权失败及重放拒绝等均通过流传播错误，HTTP/SSE 错误格式由 Web 层转换。
     *
     * @param cursor  目标调用及本次订阅的起始排他游标，断线重连可使用客户端最后处理的事件序号
     * @param context 本次观看的身份、权限及连接期限上下文，不能将游标视为授权凭据
     * @return 每次订阅独立、按调用内序号输出已提交事件的 Flux，不在本方法内编码 SSE 帧
     */
    @Override
    public Flux<ExecutionEvent<?>> watch(ExecutionEvent.Cursor cursor, ExecutionContext context) {
        if (localExecutionEventNotifier == null) {
            // 兼容仅装配结果读取和重放的场景，明确拒绝实时订阅。
            return Flux.error(new AdmissionException(ResultCodeEnum.AI_EVENT_WATCH_NOT_ENABLED));
        }
        return Flux.defer(() -> {
            // 在实际订阅时创建状态，同一个 Flux 被多次订阅也不会共享游标或互相结束。
            // after 是本服务已向下游发出的序号，不代表浏览器已经收到或确认；重连位置由客户端提供。
            var gate = new ConnectionAuthorization(cursor.executionId(), context);
            var after = new AtomicLong(cursor.afterSequence());
            log.debug("AI event subscription started, invocationId={}, traceId={}, afterSequence={}", cursor.executionId(), context.traceId(), after.get());
            // 单次完成信号，用于停止通知流和定时授权流，释放监听及调度资源。
            var done = Sinks.<Void>one();
            // 本次订阅是否发出过终态或预算事件；UNKNOWN 时据此决定空页后是否继续等待核对。
            var outcomeDelivered = new AtomicBoolean();
            Duration remaining = Duration.between(clock.instant(), context.deadline());
            if (remaining.isNegative() || remaining.isZero()) {
                // 不建立监听；仍执行授权入口，使过期上下文按正常规则返回期限错误。
                return gate.check().thenMany(Flux.empty());
            }
            var live = gate.check().flatMapMany(current -> {
                var owner = ExecutionOwner.from(current);
                // 确认调用归属后才注册监听；授权刷新不允许身份变化，因此后续可沿用此 owner。
                return executionStore.find(owner, cursor.executionId())
                        .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                        .flatMapMany(ignored -> localExecutionEventNotifier.watch(owner, cursor.executionId())
                                // notifier 先登记监听再发初始 0L；合并提示不会删除数据库事件。
                                // 将后续处理移出通知发送线程，预取 1 个提示；JDBC 调度仍由存储实现负责。
                                .publishOn(Schedulers.parallel(), 1)
                                // 0L 要求主动补读；正数只用于过滤旧提示，不能用它跳过中间事件。
                                .filter(hint -> hint == 0L || hint > after.get())
                                // 每次补读前重新授权，concatMap 串行排空历史，防止并发读取同一游标。
                                .concatMap(hint -> gate.check()
                                        .thenMany(drain(owner, cursor.executionId(), after, done, outcomeDelivered, false)), 1));
            });
            // 只重新检查授权，不定时读 Invocation/Event 表。连接租期结束后客户端重新认证连接。
            var reauthorize = Flux.interval(REAUTHORIZE_INTERVAL)
                    .concatMap(ignored -> gate.check(), 1)
                    // 不把授权上下文转成业务事件；持续检查直到被取消，权限错误仍向合并流传播。
                    .thenMany(Flux.<ExecutionEvent<?>>never());
            // 完成各生产流，再让 merge 排空已缓冲的最后一个结算事件；不能在 merge 外用 done 截断慢订阅者。
            return Flux.merge(1, live.takeUntilOther(done.asMono()), reauthorize.takeUntilOther(done.asMono()))
                    // 连接期限是硬性停止条件，可截断仍未消费的数据；客户端从自己的游标重连补读。
                    .takeUntilOther(Mono.delay(remaining))
                    .doOnError(error -> log.warn("AI event subscription failed, invocationId={}, traceId={}, afterSequence={}, code={}, type={}",
                            cursor.executionId(), context.traceId(), after.get(),
                            error instanceof AdmissionException rejected ? rejected.getResultCode().name() : "EVENT_WATCH_FAILED", error.getClass().getName()))
                    .doFinally(signal -> log.debug("AI event subscription ended, invocationId={}, traceId={}, afterSequence={}, signal={}",
                            cursor.executionId(), context.traceId(), after.get(), signal));
        });
    }

    /**
     * 串行读取当前游标之后的事件，并在需要时连续读取下一页、确认订阅是否可以结束。
     * 调用方已完成 READ 授权与调用归属检查；本方法按 owner 读取存储，不单独重新授权。
     * <p>
     * 非空页按序输出并推进游标；满页或页尾为 TERMINAL/BUDGET_CHANGED 时继续读，以排空后续事件并检查结束条件。
     * 其他不足一页的数据发出后返回，继续等待唤醒，不在本方法中定时查询。
     * 空页时检查调用终态和本次预算：未终态、仍为 RESERVED 或 UNKNOWN 尚未输出相关事件时保持订阅。
     * 预算查询器未装配时跳过预算等待；PENDING_RECONCILIATION 允许结束本次观看，但不表示费用已确定或预算已释放。
     * <p>
     * 首次空页读取可能早于终态或结算事务提交，因此确认结束条件后再递归读取一次。
     * 只有该次补读也为空才触发 done；若补读有事件则先输出，防止关闭连接时漏掉最后的结算事件。
     * 这次结束确认不保证今后不再产生事件，UNKNOWN 的后续核对仍可通过重新订阅读取。
     *
     * @param owner             已验证的租户、工作空间及主体归属
     * @param id                目标平台调用 ID
     * @param after             本次订阅共享的已发出序号，实际输出事件时推进，通知提示不修改它
     * @param done              本次订阅的结束信号，排空且确认结束后发出，停止外层通知及授权流
     * @param outcomeDelivered  本次订阅是否已发出 TERMINAL 或 BUDGET_CHANGED，用于 UNKNOWN 的等待判断
     * @param confirmedFinished 是否已在前一次空页后确认终态与预算结束条件，true 表示正在进行关闭前的补读
     * @return 本轮应发出的已提交事件；暂无数据时为空流，读取拒绝及存储错误通过 Flux 传播
     */
    private Flux<ExecutionEvent<?>> drain(ExecutionOwner owner, String id, AtomicLong after, Sinks.One<Void> done,
                                          AtomicBoolean outcomeDelivered, boolean confirmedFinished) {
        // defer 保证每次递归实际订阅时使用最新 after，不提前捕获旧游标。
        return Flux.defer(() -> executionEventStore.replay(owner, new ExecutionEvent.Cursor(id, after.get()), WATCH_PAGE_SIZE)
                .flatMapMany(outcome -> {
                    if (!outcome.successful()) {
                        // 将游标过期等存储拒绝转成订阅错误，不能跳过已经裁剪的历史继续输出。
                        return Flux.error(AdmissionException.fromStoreRejection(outcome.code()));
                    }
                    var page = outcome.value();
                    if (page.events().isEmpty()) {
                        if (confirmedFinished) {
                            // 已确认结束后的最后一次补读仍为空，可通知外层结束；不是模型取消信号。
                            done.tryEmitEmpty();
                            return Flux.empty();
                        }
                        return executionStore.find(owner, id).flatMapMany(invocation -> {
                            // UNKNOWN 虽属于终态，但从已追平游标重连且本次尚无相关事件时，继续等待核对。
                            if (!invocation.state().terminal() || invocation.state() == Invocation.State.UNKNOWN
                                    && !outcomeDelivered.get()) {
                                return Flux.empty();
                            }
                            // 仅 RESERVED 阻止结束；待对账表示本次处理结果已记录，费用仍可能未知。
                            var finished = budgetStatus == null ? Mono.just(true)
                                    : budgetStatus.resolve(invocation).map(InvocationBudgetState::outcomeAvailable);
                            // 空页与终态／结算提交可能竞争。确认二者后再读一遍，才能关闭连接。
                            return finished.flatMapMany(value -> value
                                    ? drain(owner, id, after, done, outcomeDelivered, true) : Flux.empty());
                        });
                    }
                    var last = page.events().getLast().kind();
                    // 满页可能还有下一页；页尾终态或预算事件还需读空页确认调用与账本状态。
                    boolean checkFinish = page.events().size() == WATCH_PAGE_SIZE
                            || last == ExecutionEvent.Kind.TERMINAL || last == ExecutionEvent.Kind.BUDGET_CHANGED;
                    // 存储按序返回；过滤已发出的事件，游标只随着实际向下游发出事件而前进。
                    return Flux.fromIterable(page.events()).filter(event -> event.sequence() > after.get())
                            .doOnNext(event -> {
                                after.set(event.sequence());
                                if (event.kind() == ExecutionEvent.Kind.TERMINAL || event.kind() == ExecutionEvent.Kind.BUDGET_CHANGED) {
                                    outcomeDelivered.set(true);
                                }
                            })
                            // 当前页消费完才构造下一页读取，确保游标已更新且事件顺序不被并发打乱。
                            .concatWith(Flux.defer(() -> checkFinish
                                    ? drain(owner, id, after, done, outcomeDelivered, confirmedFinished) : Flux.empty()));
                }));
    }

    /**
     * 检查当前读取权限及归属，沿 Invocation 已提交的 ResultRef 读取结果内容。
     * <p>
     * 调用不存在时返回 AI_NOT_FOUND 错误；尚未关联结果引用时返回 AI_RESULT_NOT_AVAILABLE，
     * 不扫描结果表寻找尚未提交关联的孤立内容，也不等待任务完成或重新执行任务。
     * 存在引用时由结果存储校验内容的归属、类型、版本和摘要；已提交引用对应的内容缺失属于存储一致性错误。
     * 返回值可能是完整结果或调用已提交的部分结果，不通过本方法推断调用成功或预算已结算。
     *
     * @param invocationId 要读取结果的平台调用 ID
     * @param context      本次读取的身份、权限及期限上下文
     * @return 已提交引用对应的结果 Mono；结果未就绪、内容缺失或校验失败均以错误信号返回
     */
    @Override
    public Mono<InvocationResult> result(String invocationId, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.READ).flatMap(current -> {
            var owner = ExecutionOwner.from(current);
            return executionStore.find(owner, invocationId)
                    .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                    .flatMap(invocation -> {
                        // 以调用记录中的引用作为可见性依据：结果字节可能先写入，但未关联时不能对外读取。
                        if (invocation.result() == null) {
                            return Mono.error(new AdmissionException(ResultCodeEnum.AI_RESULT_NOT_AVAILABLE));
                        }
                        var one = Map.of(invocationId, invocation);
                        return readable(owner, one, current).flatMap(allowed -> {
                            if (!allowed.contains(invocationId))
                                return Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND));
                            return executionResultStore.find(owner, invocationId, invocation.result())
                                    .switchIfEmpty(Mono.error(new IllegalStateException("Committed result bytes are missing")))
                                    .flatMap(result -> readable(owner, one, current).flatMap(confirmed -> confirmed.contains(invocationId) ? Mono.just(result) : Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND))));
                        });
                    });
        });
    }
}
