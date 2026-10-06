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
import java.util.List;
import java.util.UUID;

/**
 * 首批无历史、无外部资料的用户文本组装；不静默忽略尚未支持的选择，不派发模型。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
public final class TextContextService implements ContextService {

    private final AdmissionAuthorization authorization;
    private final BindingManager bindings;
    private final ContextSnapshotStore store;
    private final NewAiProperties properties;
    private final Clock clock;

    public TextContextService(AdmissionAuthorization authorization, BindingManager bindings, ContextSnapshotStore store,
                              NewAiProperties properties, Clock clock) {
        this.authorization = authorization;
        this.bindings = bindings;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Mono<ContextSnapshot> assemble(ContextRequest request, ResolvedBinding binding, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.INVOKE)
                .flatMap(current -> bindings.resolve(binding.definition(), binding.capability().definition(), current))
                .map(actual -> {
                    if (!actual.equals(binding) || request.history() != null || !request.memoryIds().isEmpty()
                            || !request.resources().isEmpty() || request.target() != null) {
                        throw new AdmissionException(ResultCodeEnum.AI_CONTEXT_SELECTION_NOT_SUPPORTED);
                    }
                    TextInputs.validate(request.messages(), properties.limits());
                    if (request.budget().contextWindowTokens() != binding.contextWindowTokens()
                            || request.budget().reservedToolTokens() != 0) {
                        throw new AdmissionException(ResultCodeEnum.AI_CONTEXT_CAPACITY_MISMATCH);
                    }
                    var count = TextInputs.estimatedTokens(request.messages());
                    if (count > request.budget().maxInputTokens()) {
                        throw new AdmissionException(ResultCodeEnum.AI_CONTEXT_CAPACITY_EXCEEDED);
                    }
                    var now = clock.instant();
                    return new ContextSnapshot(UUID.randomUUID().toString(), null, binding.definition(), request.messages(), List.of(),
                            request.budget(), count, true, TextInputs.TOKENIZER, List.of(), TextInputs.contentDigest(request.messages()),
                            now, now.plus(properties.limits().snapshotRetention()));
                });
    }

    @Override
    public Mono<ContextSnapshot> find(String snapshotId, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.READ)
                .flatMap(current -> store.find(ExecutionOwner.from(current), snapshotId))
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_CONTEXT_NOT_FOUND)));
    }
}
