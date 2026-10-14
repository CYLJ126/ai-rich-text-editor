package com.arte.ainew.api.execution;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionEvent;
import com.arte.ainew.common.execution.LiveTextDelta;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.spi.persistence.ExecutionEventStore;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 执行事件服务
 * <p>
 * 主要操作：游标订阅／重放、检查点及结果读取等。
 * 边界：鉴权、背压与保留期明确，不重新执行任务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:40 ✾
 **/
public interface ExecutionEventService {
    record InvocationSnapshot(String invocationId, com.arte.ainew.pojo.execution.Invocation invocation,
                              com.arte.ainew.pojo.execution.InvocationBudgetState budgetState,
                              InvocationResult result) {
    }

    default Mono<java.util.List<InvocationSnapshot>> snapshots(java.util.List<String> ids, ExecutionContext context) {
        return Mono.error(new UnsupportedOperationException("Batch snapshots not configured"));
    }

    default Mono<java.util.List<InvocationSnapshot>> snapshots(java.util.List<String> ids, java.util.Map<String, Long> knownResultVersions, ExecutionContext context) {
        return snapshots(ids, context);
    }

    sealed interface WatchSignal {
        record Event(ExecutionEvent<?> value) implements WatchSignal {
        }

        record Text(LiveTextDelta value) implements WatchSignal {
        }
    }

    default Flux<WatchSignal> watchCombined(ExecutionEvent.Cursor cursor, ExecutionContext context) {
        return Flux.merge(1, watch(cursor, context).map(WatchSignal.Event::new),
                watchText(cursor.executionId(), context).map(WatchSignal.Text::new));
    }

    /**
     * HTTP 建连前的游标检查与后续流；仅服务内部使用，不序列化为客户端 DTO。
     */
    record WatchPreparation(StoreOutcome<ExecutionEventStore.Page> validation, Flux<WatchSignal> updates) {
    }

    /**
     * 建连前验证权限、归属和游标，失败仍可返回 HTTP 错误。
     * 实现可在本次短暂交接中复用检查结果；后续订阅仍须按连接策略重新授权。
     *
     * @param cursor  排他事件游标
     * @param context 本次观看的可信读取上下文
     * @return 检查结果及后续 SSE 流；默认实现保持独立复核行为
     */
    default Mono<WatchPreparation> prepareWatch(ExecutionEvent.Cursor cursor, ExecutionContext context) {
        return replay(cursor, 1, context).map(validation -> new WatchPreparation(validation, watchCombined(cursor, context)));
    }


    /**
     * 单页有界重放；保留 CURSOR_EXPIRED，不静默跳过已裁剪历史。
     */
    Mono<StoreOutcome<ExecutionEventStore.Page>> replay(ExecutionEvent.Cursor cursor, int limit, ExecutionContext context);

    /**
     * 从排他游标无遗漏切换到实时读取，仅发布已保存事件并按 sequence 去重。
     * 通知仅唤醒回读；慢消费者有界，超限明确断开并允许游标重连；长连接按策略重新授权。
     * 取消订阅只停止观看；确定终态后排空并结束，UNKNOWN 后允许再次订阅观看核对事件。
     */
    Flux<ExecutionEvent<?>> watch(ExecutionEvent.Cursor cursor, ExecutionContext context);

    /**
     * 非耐久文字预览；不推进事件游标，仍需初始授权、归属检查及长连接重新授权。
     * <p>
     * 每个 TextDelta 都经过同一条链路：
     * GenerationDispatcher.consume().doOnNext()
     * → preview(text)
     * → LiveTextNotifier.emit(LiveTextDelta)
     * → DefaultExecutionEventService.watchText()
     * → NewAiInvocationController.watchInvocation()
     * → event: text-delta
     * <p>
     * 前端收到片段后上屏：
     * invocationStream.watchInvocation()
     * → reader.read() 读取网络字节
     * → 解码完整 SSE 事件
     * → options.onText(delta)
     * → useChatSession.appendText()
     * → 更新 streamText
     * → ChatPanel 渲染
     */
    default Flux<LiveTextDelta> watchText(String invocationId, ExecutionContext context) {
        return Flux.empty();
    }

    /**
     * 先授权并读取 Invocation 的权威 ResultRef，再校验结果类型、版本、摘要及归属。
     */
    Mono<InvocationResult> result(String invocationId, ExecutionContext context);
}
