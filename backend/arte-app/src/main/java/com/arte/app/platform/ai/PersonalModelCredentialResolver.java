package com.arte.app.platform.ai;

import com.arte.ainew.application.model.DefaultModelDraftService;
import com.arte.ainew.application.model.DefaultPersonalModelPublicationService;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.infrastructure.http.GenerationException;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.model.ModelDrafts;
import com.arte.ainew.pojo.model.ModelPublication;
import com.arte.ainew.pojo.model.PersonalModels;
import com.arte.ainew.spi.credential.ConnectionCredentialResolver;
import com.arte.ainew.spi.model.ModelCredentialCipher;
import com.arte.ainew.spi.model.ModelDraftStore;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.ainew.spi.model.PersonalModelStore;
import com.arte.ainew.spi.persistence.ExecutionReadStore;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.spi.platform.IdentityProvider;
import com.arte.core.spi.platform.PublicTransactionRunner;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 外发边界的个人凭据用途解析器
 * <p>
 * 只为本人的真实 RUNNING 调用解密精确发布固定的凭据代次。
 * 再次检查当前身份、公共模型权限、政策、范围、发布和兼容预算；密钥仅存于本次 BearerCredential，不缓存或写回执。
 * 普通登记凭据保留原解析器；个人 SecretRef 缺失、撤销或不匹配时直接拒绝，不能回退环境变量。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 20:55 ✾
 */
public final class PersonalModelCredentialResolver implements ConnectionCredentialResolver {

    private final ConnectionCredentialResolver legacy;
    private final Supplier<ExecutionReadStore> executions;
    private final PersonalModelStore releases;
    private final ModelResourceStore models;
    private final ModelDraftStore drafts;
    private final ModelCredentialCipher cipher;
    private final DefaultPersonalModelPublicationService publications;
    private final Function<ExecutionContext, NewAiProperties> configuration;
    private final IdentityProvider identity;
    private final PublicTransactionRunner tx;
    private final Scheduler scheduler;
    private final Clock clock;

    public PersonalModelCredentialResolver(ConnectionCredentialResolver legacy, Supplier<ExecutionReadStore> executions,
                                           PersonalModelStore releases, ModelResourceStore models, ModelDraftStore drafts, ModelCredentialCipher cipher,
                                           DefaultPersonalModelPublicationService publications, Function<ExecutionContext, NewAiProperties> configuration,
                                           IdentityProvider identity, PublicTransactionRunner tx, Scheduler scheduler, Clock clock) {
        this.legacy = legacy;
        this.executions = executions;
        this.releases = releases;
        this.models = models;
        this.drafts = drafts;
        this.cipher = cipher;
        this.publications = publications;
        this.configuration = configuration;
        this.identity = identity;
        this.tx = tx;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @Override
    public Mono<BearerCredential> resolve(DefinitionRef secret, ExecutionContext context) {
        if (!secret.id().startsWith("personal-model-")) {
            return legacy.resolve(secret, context);
        }
        return Mono.defer(() -> executions.get().snapshots(ExecutionOwners.from(context), List.of(context.executionId())))
                .flatMap(rows -> {
                    if (rows.size() != 1) {
                        return Mono.error(GenerationException.beforeSend("CREDENTIAL_NOT_AVAILABLE"));
                    }
                    Invocation invocation = rows.getFirst().invocation();
                    return Mono.fromCallable(() -> tx.required(() -> resolveCurrent(secret, context, invocation))).subscribeOn(scheduler);
                }).onErrorMap(RuntimeException.class, failure -> failure instanceof GenerationException ? failure
                        : GenerationException.beforeSend("CREDENTIAL_NOT_AVAILABLE"));
    }

    private BearerCredential resolveCurrent(DefinitionRef secret, ExecutionContext context, Invocation invocation) {
        ExecutionContext stored = invocation.request().context();
        if (invocation.state() != Invocation.State.RUNNING || !stored.executionId().equals(context.executionId())
                || !ExecutionOwners.from(stored).equals(ExecutionOwners.from(context)) || !Objects.equals(stored.releaseRef(), context.releaseRef())
                || !Objects.equals(stored.budgetRef(), context.budgetRef()) || context.deadline().isAfter(stored.deadline())
                || !stored.authorization().scopes().containsAll(context.authorization().scopes())
                || !context.authorization().scopes().contains("ai:invoke") || !clock.instant().isBefore(context.deadline())) {
            throw GenerationException.beforeSend("CREDENTIAL_NOT_AVAILABLE");
        }
        Principal principal = identity.findBySubjectId(context.authorization().principal().subjectId())
                .filter(context.authorization().principal()::sameIdentityAs).orElseThrow(() -> GenerationException.beforeSend("CREDENTIAL_NOT_AVAILABLE"));
        TrustedActor actor = new TrustedActor(principal, clock.instant());
        Scope scope = new Scope(context.authorization().tenantId(), context.authorization().workspaceId());
        publications.lock(actor, scope);
        PersonalModels.Release release = releases.find(scope, invocation.request().binding(), true)
                .orElseThrow(() -> GenerationException.beforeSend("CREDENTIAL_NOT_AVAILABLE"));
        if (!release.secret().equals(secret) || publications.readiness(actor, release) != null) {
            throw GenerationException.beforeSend("CREDENTIAL_NOT_AVAILABLE");
        }
        ModelPublication publication = models.findCurrentBinding(scope.tenantId(), release.binding()).orElseThrow().publication();
        NewAiProperties config = configuration.apply(context);
        if (publication.source() != ModelPublication.Source.PERSONAL || !publication.binding().capability().definition().equals(invocation.request().capability())
                || !publication.connection().credential().equals(secret) || !config.releaseRef().equals(context.releaseRef())
                || config.bindings().stream().noneMatch(publication.binding()::equals)
                || config.grants().stream().noneMatch(grant -> grant.enabled() && grant.subjectId().equals(principal.subjectId())
                && grant.tenantId().equals(scope.tenantId()) && grant.workspaceId().equals(scope.workspaceId())
                && grant.scopes().containsAll(context.authorization().scopes()) && grant.budgetRefs().contains(context.budgetRef()))
                || config.budgets().stream().noneMatch(budget -> budget.budgetRef().equals(context.budgetRef())
                && budget.owner().equals(ExecutionOwners.from(context)) && budget.rate().equals(publication.rate().definition()))) {
            throw GenerationException.beforeSend("CREDENTIAL_NOT_AVAILABLE");
        }
        ModelDrafts.Credential credential = drafts.credential(release.resource()).orElseThrow();
        return new BearerCredential(cipher.decrypt(credential, DefaultModelDraftService.associatedData(credential.resource(),
                credential.scope(), credential.responsibleSubjectId(), credential.version())));
    }
}
