package com.arte.ainew.application.conversation;

import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionDigests;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.reference.ResourceRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.spi.persistence.AdmissionCatalogStore;
import com.arte.core.enums.ResultCodeEnum;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * 当前会话创建／归属读取门面；创建幂等由数据库仲裁，不在 JVM 缓存命令或复制执行状态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
public final class DefaultConversationService implements ConversationService {
    private final AdmissionCatalogStore admissionCatalogStore;
    private final AdmissionAuthorization authorization;
    private final Clock clock;

    public DefaultConversationService(AdmissionCatalogStore admissionCatalogStore, AdmissionAuthorization authorization, Clock clock) {
        this.admissionCatalogStore = admissionCatalogStore;
        this.authorization = authorization;
        this.clock = clock;
    }

    @Override
    public Mono<Conversation> create(String title, DefinitionRef profile, List<ResourceRef> resources, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.CONVERSATION).flatMap(current -> {
            ContractChecks.text(title, "title", 256);
            var selections = ContractChecks.list(resources, "resources", 0, ContractChecks.MAX_ITEMS);
            if (profile != null || !selections.isEmpty()) {
                throw new AdmissionException(ResultCodeEnum.AI_CONVERSATION_CONFIGURATION_NOT_SUPPORTED);
            }
            ContractChecks.id(current.idempotencyKey(), "idempotencyKey");
            var now = clock.instant();
            var conversation = new Conversation(UUID.randomUUID().toString(), ExecutionOwner.from(current), title, 0, null,
                    selections, Conversation.State.ACTIVE, now, now);
            return admissionCatalogStore.createConversationOnce(conversation, current.idempotencyKey(), AdmissionDigests.conversation(title, profile, selections))
                    .map(outcome -> {
                        if (!outcome.successful()) {
                            throw AdmissionException.fromStoreRejection(outcome.code());
                        }
                        return outcome.value();
                    });
        });
    }

    @Override
    public Mono<Conversation> find(String id, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.CONVERSATION)
                .flatMap(current -> admissionCatalogStore.findConversation(ExecutionOwner.from(current), id))
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND)));
    }

    @Override
    public Mono<Turn> turn(String conversationId, String turnId, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.CONVERSATION)
                .flatMap(current -> admissionCatalogStore.findTurn(ExecutionOwner.from(current), conversationId, turnId))
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_TURN_NOT_FOUND)));
    }

    @Override
    public Mono<List<Turn>> history(ContextRequest.HistorySelection selection, ExecutionContext context) {
        return Mono.error(new AdmissionException(ResultCodeEnum.AI_HISTORY_NOT_SUPPORTED));
    }
}
