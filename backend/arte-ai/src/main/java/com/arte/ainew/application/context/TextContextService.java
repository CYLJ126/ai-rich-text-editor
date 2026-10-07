package com.arte.ainew.application.context;

import com.arte.ainew.api.context.ContextService;
import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.support.TextInputs;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.spi.persistence.ContextSnapshotStore;
import com.arte.core.enums.ResultCodeEnum;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 用户文本及服务端有界会话历史组装；不加载外部资料，不派发模型。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
public final class TextContextService implements ContextService {

    private final AdmissionAuthorization admissionAuthorization;
    private final BindingManager bindingManager;
    private final ContextSnapshotStore contextSnapshotStore;
    private final NewAiProperties properties;
    private final Clock clock;
    private final ChatHistoryLoader chatHistoryLoader;

    public TextContextService(AdmissionAuthorization admissionAuthorization, BindingManager bindingManager, ContextSnapshotStore contextSnapshotStore,
                              NewAiProperties properties, Clock clock, ChatHistoryLoader chatHistoryLoader) {
        this.admissionAuthorization = admissionAuthorization;
        this.bindingManager = bindingManager;
        this.contextSnapshotStore = contextSnapshotStore;
        this.properties = properties;
        this.clock = clock;
        this.chatHistoryLoader = chatHistoryLoader;
    }

    @Override
    public Mono<ContextSnapshot> assemble(ContextRequest contextRequest, ResolvedBinding resolvedBinding, ExecutionContext executionContext) {
        return admissionAuthorization.require(executionContext, AdmissionAuthorization.INVOKE)
                .flatMap(currentContext -> bindingManager.resolve(resolvedBinding.definition(), resolvedBinding.capability().definition(), currentContext))
                .flatMap(actualBinding -> {
                    // 重新解析的绑定必须与调用方传入的绑定一致，避免使用已变化或不匹配的模型配置。
                    // 当前仅支持用户文本和会话历史；记忆、外部资源与目标资源尚未接入，显式拒绝而非忽略。
                    if (!actualBinding.equals(resolvedBinding) || !contextRequest.memoryIds().isEmpty()
                            || !contextRequest.resources().isEmpty() || contextRequest.target() != null) {
                        throw new AdmissionException(ResultCodeEnum.AI_CONTEXT_SELECTION_NOT_SUPPORTED);
                    }
                    TextInputs.validate(contextRequest.messages(), properties.limits());
                    // Token 容量约束必须采用固定模型绑定的上下文窗口；当前不执行工具，工具预留必须为零。
                    // 这里校验的是模型输入／输出容量，不是预算账户的金额或供应商实际计费用量。
                    if (contextRequest.budget().contextWindowTokens() != resolvedBinding.contextWindowTokens()
                            || contextRequest.budget().reservedToolTokens() != 0) {
                        throw new AdmissionException(ResultCodeEnum.AI_CONTEXT_CAPACITY_MISMATCH);
                    }
                    // 未指定 history 时不读取历史；指定时按主体归属和会话版本加载有界的完整成功回复。
                    // 同一幂等请求重放时复用原快照中的历史，避免后续轮次改变原请求的输入语义。
                    return contextRequest.history() == null ? Mono.just(new ChatHistoryLoader.Loaded(List.of(), null))
                            : chatHistoryLoader.load(contextRequest.history(), resolvedBinding.capability().definition().id(), executionContext);
                }).map(loaded -> {
                    var messages = new ArrayList<>(loaded.messages());
                    // 添加本轮用户输入的消息（即提问的问题）
                    messages.addAll(contextRequest.messages());
                    TextInputs.validateContext(messages, properties.limits());
                    var tokenCount = TextInputs.estimatedTokens(messages);
                    // 历史消息与本轮用户输入共同占用输入额度；按 UTF-8 字节数加固定开销估算 Token。
                    // 超过 maxInputTokens 就明确拒绝，不静默截断历史或用户文本；估算值不用于冒充实际计费用量。
                    if (tokenCount > contextRequest.budget().maxInputTokens()) {
                        throw new AdmissionException(ResultCodeEnum.AI_CONTEXT_CAPACITY_EXCEEDED);
                    }
                    var now = clock.instant();
                    // 构造最终输入快照：记录实际选入的历史轮次、固定绑定、消息、容量约束、估算规则及内容摘要。
                    // 未加载外部资料且未进行截断，因此 fragments、truncations 均为空；有效期由快照保留配置决定。
                    // 此处只组装快照，由协调器在受理阶段持久化；到期禁止新调用，不表示立即删除存储字节。
                    return new ContextSnapshot(UUID.randomUUID().toString(), loaded.selection(), resolvedBinding.definition(), messages, List.of(),
                            contextRequest.budget(), tokenCount, true, TextInputs.TOKENIZER, List.of(), TextInputs.contentDigest(messages),
                            now, now.plus(properties.limits().snapshotRetention()));
                });
    }

    @Override
    public Mono<ContextSnapshot> find(String snapshotId, ExecutionContext context) {
        return admissionAuthorization.require(context, AdmissionAuthorization.READ)
                .flatMap(currentContext -> contextSnapshotStore.find(ExecutionOwner.from(currentContext), snapshotId))
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_CONTEXT_NOT_FOUND)));
    }
}
