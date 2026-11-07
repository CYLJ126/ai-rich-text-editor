package com.arte.app.platform.ai;

import com.arte.ainew.application.model.DefaultModelDraftService;
import com.arte.ainew.application.model.DefaultModelPublicationService;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.infrastructure.http.GenerationException;
import com.arte.ainew.pojo.control.ControlConfiguration;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.model.ModelDrafts;
import com.arte.ainew.pojo.model.ModelPublication;
import com.arte.ainew.pojo.model.ModelPublications;
import com.arte.ainew.spi.credential.ConnectionCredentialResolver;
import com.arte.ainew.spi.model.ModelCredentialCipher;
import com.arte.ainew.spi.model.ModelDraftStore;
import com.arte.ainew.spi.model.ModelPublicationStore;
import com.arte.ainew.spi.model.ModelResourceStore;
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
 * 只为真实 RUNNING 调用解密精确发布固定的凭据代次；个人凭据限本人，租户凭据额外要求独立用途授权。
 * 再次检查当前身份、公共模型权限、政策、范围、发布和兼容预算；密钥仅存于本次 BearerCredential，不缓存或写回执。
 * 普通登记凭据保留原解析器；资源 SecretRef 缺失、撤销或不匹配时直接拒绝，不能回退环境变量。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 20:55 ✾
 */
public final class ModelPublicationCredentialResolver implements ConnectionCredentialResolver {

    private final ConnectionCredentialResolver legacy;
    private final Supplier<ExecutionReadStore> executions;
    private final ModelPublicationStore releases;
    private final ModelResourceStore models;
    private final ModelDraftStore drafts;
    private final ModelCredentialCipher cipher;
    private final DefaultModelPublicationService publications;
    private final Function<ExecutionContext, ControlConfiguration> configuration;
    private final IdentityProvider identity;
    private final PublicTransactionRunner tx;
    private final Scheduler scheduler;
    private final Clock clock;

    public ModelPublicationCredentialResolver(ConnectionCredentialResolver legacy, Supplier<ExecutionReadStore> executions,
                                              ModelPublicationStore releases, ModelResourceStore models, ModelDraftStore drafts,
                                              ModelCredentialCipher cipher, DefaultModelPublicationService publications,
                                              Function<ExecutionContext, ControlConfiguration> configuration, IdentityProvider identity,
                                              PublicTransactionRunner tx, Scheduler scheduler, Clock clock) {
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
        if (!secret.id().startsWith("model-resource-")) {
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
        ModelPublications.Release release = releases.find(scope, invocation.request().binding(), true)
                .orElseThrow(() -> GenerationException.beforeSend("CREDENTIAL_NOT_AVAILABLE"));
        if (!release.secret().equals(secret) || publications.readiness(actor, release, scope) != null) {
            throw GenerationException.beforeSend("CREDENTIAL_NOT_AVAILABLE");
        }
        ModelPublication publication = models.findCurrentBinding(scope.tenantId(), release.binding()).orElseThrow().publication();
        ControlConfiguration config = configuration.apply(context);
        if (publication.source() != ModelPublication.Source.RESOURCE || !publication.binding().capability().definition().equals(invocation.request().capability())
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
        return new BearerCredential(cipher.decrypt(credential, DefaultModelDraftService.associatedData(credential)));
    }
}
