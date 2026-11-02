package com.arte.app.platform.ai;

import com.arte.ainew.application.model.DefaultPersonalModelPublicationService;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.model.*;
import com.arte.ainew.spi.model.ModelAclAccess;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.ainew.spi.model.PersonalModelRuntime;
import com.arte.ainew.spi.model.PersonalModelStore;
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
 * 个人命名空间始终受保护，不以旧授权兜底；发现与执行均校验不可变内容及当前凭据用途。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 20:55 ✾
 */
public final class PersonalModelRuntimeAdapter implements PersonalModelRuntime {

    private final PersonalModelStore releases;
    private final ModelResourceStore models;
    private final DefaultPersonalModelPublicationService publications;
    private final IdentityProvider identity;
    private final AuthorizationService authorization;
    private final Clock clock;

    public PersonalModelRuntimeAdapter(PersonalModelStore releases, ModelResourceStore models, DefaultPersonalModelPublicationService publications,
                                       IdentityProvider identity, AuthorizationService authorization, Clock clock) {
        this.releases = releases;
        this.models = models;
        this.publications = publications;
        this.identity = identity;
        this.authorization = authorization;
        this.clock = clock;
    }

    ModelResourceVersion version(PersonalModels.Release release, boolean current) {
        ModelResourceVersion version = (current ? models.findCurrentBinding(release.scope().tenantId(), release.binding())
                : models.findBinding(release.scope().tenantId(), release.binding())).orElseThrow(() -> DefaultPersonalModelPublicationService.error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
        if (!version.resource().equals(release.resource()) || version.publication().source() != ModelPublication.Source.PERSONAL
                || !version.publication().binding().definition().equals(release.binding())
                || !Objects.equals(version.publication().connection().credential(), release.secret())
                || !version.linkedBy().equals(release.publishedBy()) || !version.linkedAt().equals(release.publishedAt())
                || !release.binding().id().equals(DefaultPersonalModelPublicationService.family(release.resource()))) {
            throw DefaultPersonalModelPublicationService.error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        }
        return version;
    }

    @Override
    public NewAiProperties contribute(TrustedActor actor, Scope scope, NewAiProperties base, boolean current) {
        return DefaultPersonalModelPublicationService.mergeDefinitions(base, releases.list(scope, current).stream()
                .map(release -> version(release, current).publication()).toList());
    }

    @Override
    public ModelAclAccess.Decision check(ExecutionContext context, List<ModelAccessCandidate> candidates) {
        Set<DefinitionRef> protectedRefs = new HashSet<>();
        Map<DefinitionRef, ModelAccessProof> allowed = new HashMap<>();
        for (ModelAccessCandidate candidate : candidates) {
            if (PersonalModels.isPersonalBinding(candidate.binding().definition())) {
                protectedRefs.add(candidate.binding().definition());
            }
        }
        if (protectedRefs.isEmpty()) {
            return new ModelAclAccess.Decision(false, Map.of(), protectedRefs);
        }
        Scope scope = new Scope(context.authorization().tenantId(), context.authorization().workspaceId());
        Principal principal = identity.findBySubjectId(context.authorization().principal().subjectId())
                .filter(context.authorization().principal()::sameIdentityAs).orElseThrow(() -> DefaultPersonalModelPublicationService.error(PlatformErrorCodeEnum.UNAUTHENTICATED));
        TrustedActor actor = new TrustedActor(principal, clock.instant());
        publications.lock(actor, scope);
        for (ModelAccessCandidate candidate : candidates) {
            DefinitionRef binding = candidate.binding().definition();
            if (!protectedRefs.contains(binding)) {
                continue;
            }
            PersonalModels.Release release = releases.find(scope, binding, true).orElse(null);
            if (release == null) {
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
                    new AccessRequest(scope, release.resource(), MODEL_USE, "personal-model-execution")).versions();
            allowed.put(binding, new ModelAccessProof(release.resource(), model.ownership(), basis,
                    Math.max(1, model.version()), ModelPublication.Source.PERSONAL, release.maxOutputTokens(), model.name()));
        }
        return new ModelAclAccess.Decision(false, allowed, protectedRefs);
    }
}
