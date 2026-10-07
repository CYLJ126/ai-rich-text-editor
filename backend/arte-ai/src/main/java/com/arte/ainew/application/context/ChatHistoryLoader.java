package com.arte.ainew.application.context;

import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.conversation.ConversationPage;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.ModelResult;
import com.arte.ainew.spi.persistence.ContextSnapshotStore;
import com.arte.ainew.spi.persistence.ExecutionResultStore;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.PageParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 加载当前会话的历史上下文，用于后续模型输入。
 * <p>
 * 仅从当前主体会话及已提交的完整结果加载最近十轮；失败／部分输出不进入模型上下文。
 * 幂等重放使用原快照中的历史，防止后续轮次或结果变化改变原请求语义。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 20:57 ✾
 */
@Slf4j
public final class ChatHistoryLoader {

    /**
     * 已加载的历史上下文，由 TextContextService 再追加本轮用户输入。
     *
     * @param messages  历史消息，按轮次顺序排列为 USER／ASSISTANT 对，不包含本轮用户输入。
     * @param selection 实际选入历史的会话版本及 Turn ID 列表；没有选入历史时为 null。
     */
    public record Loaded(List<ChatMessage> messages, ContextRequest.HistorySelection selection) {
    }

    /**
     * 一轮可用于后续模型输入的完整历史，关联原用户输入与规范化后的助手回答。
     *
     * @param turn   已校验关联关系的原历史轮次，提供用户消息和轮次标识。
     * @param answer 从完整成功结果中按原顺序合并得到的纯文本 ASSISTANT 消息。
     */
    private record Completed(Turn turn, ChatMessage answer) {
    }

    /**
     * 单次历史选择最多检查最近十轮，限制读取范围及上下文规模。
     * 先截取最近轮次，再筛选完整成功结果；跳过失败轮次后，不继续向更早历史补足十轮。
     */
    public static final int MAX_TURNS = 10;

    private final ConversationService conversationService;
    private final AdmissionAuthorization admissionAuthorization;
    private final ExecutionStore executionStore;
    private final ContextSnapshotStore contextSnapshotStore;
    private final ExecutionResultStore executionResultStore;

    public ChatHistoryLoader(ConversationService conversationService, AdmissionAuthorization admissionAuthorization,
                             ExecutionStore executionStore, ContextSnapshotStore contextSnapshotStore, ExecutionResultStore executionResultStore) {
        this.conversationService = conversationService;
        this.admissionAuthorization = admissionAuthorization;
        this.executionStore = executionStore;
        this.contextSnapshotStore = contextSnapshotStore;
        this.executionResultStore = executionResultStore;
    }

    /**
     * 加载本次提交所需的历史消息，不包含本轮用户输入。
     * selection 描述会话 ID、固定会话版本及最近轮次上限，本身不携带历史消息；当前不支持指定 Turn ID。
     * <p>
     * 校验会话权限及主体归属后，按主体、能力 ID 和幂等键查找原受理记录，根据是否查到 Invocation 选择加载路径：
     * <ol>
     *     <li>查到原调用：按幂等重试处理，要求选择的会话 ID、提交版本与原调用一致。
     *     通过原调用的 contextSnapshotId 读取已保存的 ContextSnapshot，恢复第一次受理时选入的历史。
     *     原快照包含历史消息和原本轮用户输入，因此去掉最后一条用户消息后返回；不重新选择最新历史，
     *     也不要求当前会话版本仍等于原提交版本。</li>
     *     <li>未查到原调用：按新提交处理，要求选择版本等于当前会话版本。
     *     版本为零时返回空历史；否则先选取最近 maxTurns 轮（最多十轮），再通过
     *     {@link #completed(Turn, ExecutionOwner)} 读取对应的权威执行状态及已提交结果。
     *     仅保留完整成功的纯文本回答，按轮次顺序组成 USER／ASSISTANT 消息对；跳过的轮次不向更早历史补足。
     *     活动或非用户停止的 UNKNOWN 调用会拒绝加载；耐久停止的生成轮次跳过，部分回复只保留展示。
     *     关联、存储或不支持的内容异常也会向调用方传播。</li>
     * </ol>
     * 有历史时仍需校验 ai:read 权限。返回后由 {@link TextContextService} 追加本次请求的用户输入；
     * 受理协调器再核对完整请求语义，决定返回原回执、报告幂等冲突或受理新调用。
     *
     * @param selection        历史选择条件，不能为空；turnIds 必须为空，maxTurns 为 1～MAX_TURNS，按固定会话版本选择最近轮次。
     * @param capabilityId     本次提交的能力 ID，与主体归属和幂等键共同确定原调用的查询范围。
     * @param executionContext 当前已认证执行的上下文，提供主体归属、权限、截止时间和本次操作的幂等键；重试应沿用原幂等键。
     * @return 历史消息及实际选入的 Turn ID／会话版本；无历史时返回 messages 为空、selection 为 null 的 Loaded，
     * 而非空 Mono；权限、版本、幂等冲突或历史数据异常通过 onError 返回。
     */
    public Mono<Loaded> load(ContextRequest.HistorySelection selection, String capabilityId, ExecutionContext executionContext) {
        if (!selection.turnIds().isEmpty() || selection.maxTurns() > MAX_TURNS) {
            // 当前只支持按固定会话版本选择最近轮次，不支持指定 Turn ID 或超过十轮的选择。
            return Mono.error(new AdmissionException(ResultCodeEnum.AI_CONTEXT_SELECTION_NOT_SUPPORTED));
        }
        var owner = ExecutionOwner.from(executionContext);
        return conversationService.find(selection.conversationId(), executionContext).flatMap(conversation ->
                // 先按主体归属、能力 ID 和幂等键查找原受理记录，再决定是否检查当前会话版本。
                // 原调用受理后会话版本可能已推进，重放必须优先恢复原输入历史。
                executionStore.findAccepted(owner, capabilityId, executionContext.idempotencyKey()).flatMap(originalInvocation -> {
                    if (originalInvocation.conversation() == null
                            || !originalInvocation.conversation().conversationId().equals(selection.conversationId())
                            || originalInvocation.conversation().conversationVersion() != selection.conversationVersion()) {
                        // 同一幂等键对应的原调用必须属于相同会话及相同提交版本，否则是幂等语义冲突。
                        // 用户文本、生成参数等其余请求语义仍由受理协调器核对。
                        return Mono.error(new AdmissionException(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT));
                    }
                    // 使用原受理快照固定历史；缺失快照属于存储异常，不能改读最新历史来替代。
                    // 去掉快照最后一条原用户输入，仅返回历史；本轮输入由 TextContextService 重新追加。
                    return contextSnapshotStore.find(owner, originalInvocation.contextSnapshotId())
                            .switchIfEmpty(Mono.error(new IllegalStateException("Accepted executionContext bytes are missing")))
                            .flatMap(contextSnapshot -> {
                                log.debug("AI history restored from accepted snapshot, invocationId={}, originalInvocationId={}, traceId={}, conversationId={}, snapshotId={}",
                                        executionContext.executionId(), originalInvocation.request().context().executionId(), executionContext.traceId(),
                                        selection.conversationId(), contextSnapshot.snapshotId());
                                var loaded = new Loaded(contextSnapshot.messages().subList(0, contextSnapshot.messages().size() - 1), contextSnapshot.history());
                                // 无历史时不需要额外的历史读取权限；有历史时，即使是幂等重放也重新校验 ai:read。
                                return loaded.messages().isEmpty() ? Mono.just(loaded)
                                        : admissionAuthorization.require(executionContext, AdmissionAuthorization.READ).thenReturn(loaded);
                            });
                }).switchIfEmpty(Mono.defer(() -> {
                    if (selection.conversationVersion() != conversation.version()) {
                        log.warn("AI history version conflict, invocationId={}, traceId={}, conversationId={}, expectedVersion={}, actualVersion={}",
                                executionContext.executionId(), executionContext.traceId(), conversation.conversationId(), selection.conversationVersion(), conversation.version());
                        // 仅新提交要求选择版本与当前会话版本一致，防止把新追加轮次混入旧版本请求。
                        return Mono.error(new AdmissionException(ResultCodeEnum.AI_VERSION_CONFLICT));
                    }
                    // 新会话版本为零，尚未受理任何轮次，直接返回空历史且不创建历史选择记录。
                    if (conversation.version() == 0) return Mono.just(new Loaded(List.of(), null));
                    return admissionAuthorization.require(executionContext, AdmissionAuthorization.READ).flatMap(currentContext -> {
                        // sequence 等于受理后的会话版本；至多读取两页以覆盖最近 maxTurns 轮。
                        long last = (conversation.version() - 1) / selection.maxTurns() + 1;
                        var pages = last == 1 ? List.of(last) : List.of(last - 1, last);
                        return Flux.fromIterable(pages).concatMap(page -> {
                            var pagination = new PageParam();
                            pagination.setCurrent(page);
                            pagination.setSize((long) selection.maxTurns());
                            // 每页读取都校验当前主体的会话权限、归属和固定版本；读取期间版本变化会明确冲突。
                            return conversationService.turns(conversation.conversationId(), conversation.version(), pagination, currentContext);
                        }).flatMapIterable(ConversationPage::records).collectList().flatMap(turns -> {
                            var recent = turns.stream().sorted(Comparator.comparingLong(Turn::sequence))
                                    .skip(Math.max(0, turns.size() - selection.maxTurns())).toList();
                            // 按历史顺序逐轮读取权威执行状态和已提交结果；concatMap 保留顺序。
                            // 已知终态及耐久停止的生成轮次返回 empty 后跳过，其他活动／未知状态或数据异常终止加载。
                            return Flux.fromIterable(recent).concatMap(turn -> completed(turn, owner)).collectList();
                        }).map(completed -> {
                            var messages = new ArrayList<ChatMessage>();
                            completed.forEach(turn -> {
                                messages.add(turn.turn().userMessage());
                                messages.add(turn.answer());
                            });
                            // 将“最近轮次”的选择条件收敛为实际选入的 Turn ID，供快照记录确切历史来源。
                            // 若没有可用的完整成功回复，history 必须为 null，不能保存空 Turn ID 的历史选择。
                            var actual = completed.isEmpty() ? null : new ContextRequest.HistorySelection(selection.conversationId(),
                                    selection.conversationVersion(), completed.stream().map(turn -> turn.turn().turnId()).toList(), selection.maxTurns());
                            return new Loaded(List.copyOf(messages), actual);
                        });
                    });
                })));
    }

    /**
     * 从一轮历史中选取可用于后续模型输入的完整纯文本回答。
     * 优先使用显式选中的 Invocation，未显式选择时使用最后一个候选；仅读取权威提交的结果引用。
     *
     * @param turn  待检查的历史轮次，候选关联来自已受理的会话记录。
     * @param owner 当前执行主体的租户、工作空间和主体归属，用于隔离执行及结果读取。
     * @return 合格轮次及合并后的助手回答；已知失败、部分或非生成结果返回 empty。
     * 活动／非用户停止的 UNKNOWN 状态、关联错误、结果字节缺失或不支持的内容通过 onError 拒绝加载。
     */
    private Mono<Completed> completed(Turn turn, ExecutionOwner owner) {
        var id = turn.selectedInvocationId() != null ? turn.selectedInvocationId() : turn.invocationIds().getLast();
        return executionStore.find(owner, id).switchIfEmpty(Mono.error(new IllegalStateException("History invocation is missing")))
                .flatMap(invocation -> {
                    // 候选 Invocation 必须同时关联目标会话和目标 Turn，不能仅凭列表中的 ID 信任其来源。
                    if (invocation.conversation() == null || !invocation.conversation().conversationId().equals(turn.conversationId())
                            || !invocation.conversation().turnId().equals(turn.turnId())) {
                        return Mono.error(new IllegalStateException("History invocation does not match its turn"));
                    }
                    if (invocation.userStoppedGeneration()) {
                        // 停止不代表费用为零；只跳过这一轮的模型输入，原结果与预算仍由原 Invocation 管理。
                        return executionStore.cancellationRequested(owner, id).flatMap(cancelled -> cancelled
                                ? Mono.<Completed>empty()
                                : Mono.error(new AdmissionException(ResultCodeEnum.AI_CONVERSATION_BUSY)));
                    }
                    // 普通 UNKNOWN 尚未确认远端结果，不能把断流或超时当作用户停止。
                    if (!invocation.state().terminal() || invocation.state() == Invocation.State.UNKNOWN) {
                        log.debug("AI history blocked by unresolved invocation, conversationId={}, turnId={}, historyInvocationId={}, state={}",
                                turn.conversationId(), turn.turnId(), id, invocation.state());
                        return Mono.error(new AdmissionException(ResultCodeEnum.AI_CONVERSATION_BUSY));
                    }
                    // 仅保留成功且具有完整结果引用的调用；失败、取消等已知终态及部分结果不进入模型历史。
                    if (invocation.state() != Invocation.State.SUCCEEDED || invocation.result() == null || invocation.result().partial()) {
                        log.debug("AI history turn skipped, conversationId={}, turnId={}, historyInvocationId={}, state={}, resultAvailable={}, partialResult={}",
                                turn.conversationId(), turn.turnId(), id, invocation.state(), invocation.result() != null,
                                invocation.result() != null && invocation.result().partial());
                        return Mono.empty();
                    }
                    return executionResultStore.find(owner, id, invocation.result())
                            .switchIfEmpty(Mono.error(new IllegalStateException("Committed history result bytes are missing")))
                            .flatMap(result -> {
                                // 再核对结果字节的能力类型与模型输出完整性，不能只依赖 Invocation 的成功状态。
                                if (!(result instanceof InvocationResult.Generation(
                                        ModelResult value
                                )) || !value.complete()) return Mono.empty();
                                var outputs = value.outputs();
                                // 当前历史链路只支持非空的纯文本助手输出；工具调用、工具关联及多模态内容均显式拒绝。
                                if (outputs.isEmpty() || outputs.stream().anyMatch(message -> !message.toolCalls().isEmpty()
                                        || message.toolCallId() != null || message.content().stream().anyMatch(part -> !(part instanceof ChatMessage.Text)))) {
                                    return Mono.error(new AdmissionException(ResultCodeEnum.AI_CONTEXT_SELECTION_NOT_SUPPORTED));
                                }
                                // 本阶段为纯文本，不执行工具；合并一轮的完整 assistant 输出并保留次序。
                                var parts = outputs.stream().flatMap(message -> message.content().stream()).toList();
                                return Mono.just(new Completed(turn, new ChatMessage("history-" + turn.turnId(),
                                        ChatMessage.Role.ASSISTANT, parts, List.of(), null)));
                            });
                });
    }
}
