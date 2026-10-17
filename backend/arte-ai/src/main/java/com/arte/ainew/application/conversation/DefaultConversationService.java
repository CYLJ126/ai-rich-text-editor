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
import com.arte.ainew.pojo.conversation.ConversationList;
import com.arte.ainew.pojo.conversation.ConversationPage;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.spi.conversation.ConversationArchiveReader;
import com.arte.ainew.spi.persistence.AdmissionCatalogStore;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.PageParam;
import com.arte.core.utils.MybatisPages;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public final class DefaultConversationService implements ConversationService {
    private final AdmissionCatalogStore admissionCatalogStore;
    private final AdmissionAuthorization authorization;
    private final Clock clock;
    private final ConversationArchiveReader archives;

    public DefaultConversationService(AdmissionCatalogStore admissionCatalogStore, AdmissionAuthorization authorization, Clock clock) {
        this(admissionCatalogStore, authorization, clock, null);
    }

    public DefaultConversationService(AdmissionCatalogStore admissionCatalogStore, AdmissionAuthorization authorization, Clock clock,
                                      ConversationArchiveReader archives) {
        this.admissionCatalogStore = admissionCatalogStore;
        this.authorization = authorization;
        this.clock = clock;
        this.archives = archives;
    }

    @Override
    public Mono<Conversation> create(String title, DefinitionRef profile, List<ResourceRef> resources, ExecutionContext context) {
        return create(title, profile, resources, context, AdmissionDigests.conversation(title, profile, resources));
    }

    private Mono<Conversation> create(String title, DefinitionRef profile, List<ResourceRef> resources, ExecutionContext context, String digest) {
        return authorization.requireWrite(context, AdmissionAuthorization.CONVERSATION).flatMap(current -> {
            ContractChecks.text(title, "title", 256);
            var selections = ContractChecks.list(resources, "resources", 0, ContractChecks.MAX_ITEMS);
            if (profile != null || !selections.isEmpty()) {
                throw new AdmissionException(ResultCodeEnum.AI_CONVERSATION_CONFIGURATION_NOT_SUPPORTED);
            }
            ContractChecks.id(current.idempotencyKey(), "idempotencyKey");
            var now = clock.instant();
            var conversation = new Conversation(UUID.randomUUID().toString(), ExecutionOwner.from(current), title, 0, null,
                    selections, Conversation.State.ACTIVE, now, now);
            return admissionCatalogStore.createConversationOnce(conversation, current.idempotencyKey(), digest)
                    .map(outcome -> {
                        if (!outcome.successful()) {
                            log.warn("AI conversation creation rejected, invocationId={}, traceId={}, code={}", current.executionId(), current.traceId(), outcome.code());
                            throw AdmissionException.fromStoreRejection(outcome.code());
                        }
                        log.info("AI conversation creation committed, invocationId={}, traceId={}, conversationId={}, outcome={}",
                                current.executionId(), current.traceId(), outcome.value().conversationId(), outcome.code());
                        return outcome.value();
                    });
        });
    }

    @Override
    public Mono<Conversation> createDefault(String localDate, ExecutionContext context) {
        java.time.LocalDate date;
        try {
            date = localDate == null
                    ? java.time.LocalDate.ofInstant(clock.instant(), java.time.ZoneId.of("Asia/Shanghai"))
                    : java.time.LocalDate.parse(localDate);
        } catch (java.time.format.DateTimeParseException error) {
            throw new IllegalArgumentException("localDate must be a valid yyyy-MM-dd date", error);
        }
        ContractChecks.require(localDate == null || (date.getYear() >= 1 && date.getYear() <= 9999
                && date.toString().equals(localDate)), "localDate must be a valid yyyy-MM-dd date");
        var title = "新会话" + date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        // 旧请求保留原摘要；新请求绑定点击日期，即使首次到达／首次落库已跨午夜也不变。
        var digest = localDate == null ? List.of("create-default-conversation", "Asia/Shanghai")
                : List.of("create-default-conversation", "Asia/Shanghai", localDate);
        return create(title, null, List.of(), context,
                com.arte.ainew.serialization.CanonicalJson.digest(digest));
    }

    @Override
    public Mono<ConversationList.Item> metadata(String id, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.CONVERSATION)
                .flatMap(current -> admissionCatalogStore.conversationMetadata(ExecutionOwner.from(current), id))
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND)));
    }

    @Override
    public Mono<ConversationList.Page> listCursor(ConversationList.Query query, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.CONVERSATION)
                .flatMap(current -> admissionCatalogStore.conversationList(ExecutionOwner.from(current), query));
    }

    @Override
    public Mono<ConversationList.Window> listWindow(ConversationList.WindowQuery query, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.CONVERSATION)
                .flatMap(current -> admissionCatalogStore.conversationWindow(ExecutionOwner.from(current), query));
    }

    @Override
    public Mono<ConversationList.Receipt> changeMetadata(ConversationList.Command command, ExecutionContext context) {
        ContractChecks.require(command.idempotencyKey().equals(context.idempotencyKey()), "Command key must match context");
        return authorization.requireWrite(context, AdmissionAuthorization.CONVERSATION)
                .flatMap(current -> admissionCatalogStore.changeConversationMetadata(ExecutionOwner.from(current), command))
                .map(outcome -> {
                    if (!outcome.successful()) {
                        if (outcome.code() == com.arte.ainew.pojo.execution.StoreOutcome.Code.NOT_FOUND)
                            throw new AdmissionException(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND);
                        throw AdmissionException.fromStoreRejection(outcome.code());
                    }
                    return outcome.value();
                });
    }

    @Override
    public Mono<ConversationList.CommandResult> metadataReceipt(String commandId, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.CONVERSATION)
                .flatMap(current -> admissionCatalogStore.conversationMetadataReceipt(ExecutionOwner.from(current), commandId))
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)));
    }

    @Override
    public Mono<Conversation> find(String id, ExecutionContext context) {
        // 搜索的普通打开入口复用详情，归档资格不能只在 PIT 或消息定位路径校验。
        return observe(authorization.require(context, AdmissionAuthorization.CONVERSATION)
                        .flatMap(current -> admissionCatalogStore.findConversation(ExecutionOwner.from(current), id)
                                .flatMap(value -> archives == null ? Mono.just(value)
                                        : archives.findArchivedConversationIds(ExecutionOwner.from(current), java.util.Set.of(id))
                                        .switchIfEmpty(Mono.error(new IllegalStateException("Archive qualification is unavailable")))
                                        .flatMap(excluded -> excluded.contains(id) ? Mono.error(new AdmissionException(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND)) : Mono.just(value))))
                        .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND))),
                context, "getConversation", id, null, null);
    }

    @Override
    public Mono<ConversationPage<Conversation>> list(PageParam page, ExecutionContext context) {
        return observe(authorization.require(context, AdmissionAuthorization.CONVERSATION).flatMap(current -> {
            var pagination = MybatisPages.from(page);
            return admissionCatalogStore.listConversations(ExecutionOwner.from(current), pagination.getCurrent(), pagination.getSize());
        }), context, "listConversations", null, page, null);
    }

    @Override
    public Mono<ConversationPage<Turn>> turns(String conversationId, long expectedVersion, PageParam page, ExecutionContext context) {
        return observe(authorization.require(context, AdmissionAuthorization.CONVERSATION).flatMap(current -> {
            ContractChecks.id(conversationId, "conversationId");
            ContractChecks.range(expectedVersion, "expectedVersion", 0, Long.MAX_VALUE);
            var pagination = MybatisPages.from(page);
            return admissionCatalogStore.listTurns(ExecutionOwner.from(current), conversationId, expectedVersion,
                    pagination.getCurrent(), pagination.getSize()).map(outcome -> {
                if (!outcome.successful()) {
                    if (outcome.code() == com.arte.ainew.pojo.execution.StoreOutcome.Code.NOT_FOUND) {
                        throw new AdmissionException(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND);
                    }
                    throw AdmissionException.fromStoreRejection(outcome.code());
                }
                return outcome.value();
            });
        }), context, "queryTurnsOfConversation", conversationId, page, expectedVersion);
    }

    private <T> Mono<T> observe(Mono<T> source, ExecutionContext context, String operation,
                                String conversationId, PageParam page, Long expectedVersion) {
        return Mono.defer(() -> {
            var started = System.nanoTime();
            var owner = ExecutionOwner.from(context);
            var target = conversationId == null ? null : conversationId.replaceAll("\\p{Cntrl}", "_");
            log.debug("AI conversation query started, operation={}, traceId={}, executionId={}, tenantId={}, workspaceId={}, subjectId={}, conversationId={}, current={}, size={}, expectedVersion={}",
                    operation, context.traceId(), context.executionId(), owner.tenantId(), owner.workspaceId(), owner.subjectId(),
                    target, page == null ? null : page.getCurrent(), page == null ? null : page.getSize(), expectedVersion);
            return source.doOnSuccess(result -> log.debug("AI conversation query completed, operation={}, traceId={}, executionId={}, elapsedMs={}, total={}, records={}",
                            operation, context.traceId(), context.executionId(), (System.nanoTime() - started) / 1_000_000,
                            result instanceof ConversationPage<?> value ? value.total() : null,
                            result instanceof ConversationPage<?> value ? value.records().size() : null))
                    .doOnError(error -> log.warn("AI conversation query failed, operation={}, traceId={}, executionId={}, tenantId={}, workspaceId={}, subjectId={}, conversationId={}, current={}, size={}, expectedVersion={}, elapsedMs={}, type={}",
                            operation, context.traceId(), context.executionId(), owner.tenantId(), owner.workspaceId(), owner.subjectId(),
                            target, page == null ? null : page.getCurrent(), page == null ? null : page.getSize(), expectedVersion,
                            (System.nanoTime() - started) / 1_000_000, error.getClass().getName()));
        });
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
