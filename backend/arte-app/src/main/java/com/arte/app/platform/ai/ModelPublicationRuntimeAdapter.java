package com.arte.app.platform.ai;

import com.arte.ainew.application.model.DefaultModelPublicationService;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.model.*;
import com.arte.ainew.spi.model.*;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.platform.AuthorizationVersions;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.spi.platform.IdentityProvider;

import java.time.Clock;
import java.util.*;

import static com.arte.core.constant.platform.PlatformActions.MODEL_USE;

/**
 * 将空间发布和显式准入的租户模型接入唯一控制目录，不修改原 Grant、预算或执行主体。
 * 资源发布命名空间始终受保护，不以旧授权兜底；发现与执行均校验不可变内容及当前凭据用途。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 20:55 ✾
 */
public final class ModelPublicationRuntimeAdapter implements ModelPublicationRuntime {

    private final ModelPublicationStore releases;
    private final ModelResourceStore models;
    private final DefaultModelPublicationService publications;
    private final IdentityProvider identity;
    private final AuthorizationService authorization;
    private final Clock clock;

    private ModelSelectionSource selections = (scope, current) -> List.of();

    /**
     * 安装权威启用读取端口；缺少端口时保持无启用模型，不能以发布代替空间选择。
     */
    public void selections(ModelSelectionSource selections) {
        this.selections = Objects.requireNonNull(selections);
    }

    public boolean isSelected(Scope scope, ModelPublications.Release release, boolean current) {
        return selections.list(scope, current).stream().anyMatch(selection -> selection.resourceId().equals(release.resource().resourceId())
                && selection.binding().equals(release.binding()));
    }

    public ModelPublicationRuntimeAdapter(ModelPublicationStore releases, ModelResourceStore models, DefaultModelPublicationService publications,
                                          IdentityProvider identity, AuthorizationService authorization, Clock clock) {
        this.releases = releases;
        this.models = models;
        this.publications = publications;
        this.identity = identity;
        this.authorization = authorization;
        this.clock = clock;
    }

    public ModelResourceVersion version(ModelPublications.Release release, boolean current) {
        ModelResourceVersion version = (current ? models.findCurrentBinding(release.scope().tenantId(), release.binding())
                : models.findBinding(release.scope().tenantId(), release.binding())).orElseThrow(() -> DefaultModelPublicationService.error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
        if (!version.resource().equals(release.resource()) || version.publication().source() != ModelPublication.Source.RESOURCE
                || !version.publication().binding().definition().equals(release.binding())
                || !Objects.equals(version.publication().planRef(), release.planRef()) || version.publication().credentialKind() != release.credentialKind()
                || !Objects.equals(version.publication().connection().credential(), release.secret())
                || !Objects.equals(version.publication().catalogRevision(), release.catalogRevision())
                || (version.publication().binding().contextWindowTokens() == null || version.publication().binding().contextWindowTokens() <= release.maxOutputTokens())
                || !version.linkedBy().equals(release.publishedBy()) || !version.linkedAt().equals(release.publishedAt())
                || !release.binding().id().equals(DefaultModelPublicationService.family(release.resource()))) {
            throw DefaultModelPublicationService.error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        }
        return version;
    }

    @Override
    public NewAiProperties contribute(TrustedActor actor, Scope scope, NewAiProperties base, boolean current) {
        return DefaultModelPublicationService.mergeDefinitions(base, releases.list(scope, current).stream()
                .filter(release -> isSelected(scope, release, current))
                .map(release -> version(release, current).publication()).toList());
    }

    @Override
    public ModelAclAccess.Decision check(ExecutionContext context, List<ModelAccessCandidate> candidates) {
        Set<DefinitionRef> protectedRefs = new HashSet<>();
        Map<DefinitionRef, ModelAccessProof> allowed = new HashMap<>();
        for (ModelAccessCandidate candidate : candidates) {
            if (ModelPublications.isResourceBinding(candidate.binding().definition())) {
                protectedRefs.add(candidate.binding().definition());
            }
        }
        if (protectedRefs.isEmpty()) {
            return new ModelAclAccess.Decision(false, Map.of(), protectedRefs);
        }
        Scope scope = new Scope(context.authorization().tenantId(), context.authorization().workspaceId());
        Principal principal = identity.findBySubjectId(context.authorization().principal().subjectId())
                .filter(context.authorization().principal()::sameIdentityAs).orElseThrow(() -> DefaultModelPublicationService.error(PlatformErrorCodeEnum.UNAUTHENTICATED));
        TrustedActor actor = new TrustedActor(principal, clock.instant());
        publications.lock(actor, scope);
        for (ModelAccessCandidate candidate : candidates) {
            DefinitionRef binding = candidate.binding().definition();
            if (!protectedRefs.contains(binding)) {
                continue;
            }
            ModelPublications.Release release = releases.find(scope, binding, true).orElse(null);
            if (release == null || !isSelected(scope, release, true)) {
                continue;
            }
            ModelResourceVersion version = version(release, true);
            publications.lock(actor, release.scope());
            ModelResource model = publications.current(release.scope(), release.resource());
            PlatformModelAclAccess.validate(scope, candidate, version, model);
            if (publications.readiness(actor, release, scope) != null) {
                continue;
            }
            AuthorizationVersions basis = authorization.requireInCurrentTransaction(actor,
                    new AccessRequest(scope, release.resource(), MODEL_USE, "model-resource-execution")).versions();
            allowed.put(binding, new ModelAccessProof(release.resource(), model.ownership(), basis,
                    Math.max(1, model.version()), ModelPublication.Source.RESOURCE, release.maxOutputTokens(), model.name()));
        }
        return new ModelAclAccess.Decision(false, allowed, protectedRefs);
    }
}
