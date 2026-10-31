package com.arte.app.platform.ai;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.pojo.model.*;
import com.arte.ainew.spi.model.ModelAclAccess;
import com.arte.ainew.spi.model.ModelAclScopeStore;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.AccessDecision;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.platform.AuthorizationVersions;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.spi.platform.IdentityProvider;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.util.*;

import static com.arte.core.constant.platform.PlatformActions.MODEL_USE;
import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_AI_USE;

/**
 * 执行资格的公共模型 ACL 适配。迁移记录是唯一切换依据，SQL 故障不会解释为未迁移。
 * 展示读取使用独立只读快照；最终受理／派发复核只加入调用方原写事务。
 * 父租户锁使迁移、撤权、模型停用与最终执行提交按同一协议串行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 12:00 ✾
 */
public final class PlatformModelAclAccess implements ModelAclAccess {

    private final ModelAclScopeStore scopes;
    private final ModelResourceStore models;
    private final IdentityProvider identity;
    private final PublicTransactionRunner tx;
    private final AuthorizationService authorization;
    private final WorkspaceGovernanceLocks locks;
    private final Scheduler scheduler;
    private final Clock clock;

    public PlatformModelAclAccess(ModelAclScopeStore scopes, ModelResourceStore models, IdentityProvider identity,
                                  PublicTransactionRunner tx, AuthorizationService authorization, TenantStore tenants, WorkspaceStore workspaces,
                                  TenantMembershipStore tenantMembers, WorkspaceMembershipStore members, PreferenceStore preferences,
                                  Scheduler scheduler, Clock clock) {
        this.scopes = scopes;
        this.models = models;
        this.identity = identity;
        this.tx = tx;
        this.authorization = authorization;
        this.scheduler = scheduler;
        this.clock = clock;
        this.locks = new WorkspaceGovernanceLocks(tx, tenants, workspaces, tenantMembers, members, preferences);
    }

    @Override
    public Mono<Decision> check(ExecutionContext context, AiEntitlement.Source source, List<ModelAccessCandidate> candidates) {
        List<ModelAccessCandidate> inputs = List.copyOf(candidates);
        return Mono.fromCallable(() -> tx.readOnly(() -> {
            Scope scope = scope(context);
            Optional<ModelAclScope> manifest = scopes.find(scope);
            return manifest.map(modelAclScope -> decide(actor(context, false), modelAclScope, source, inputs, false))
                    .orElseGet(() -> new Decision(false, Map.of()));
        })).subscribeOn(scheduler);
    }

    @Override
    public Decision checkCurrent(ExecutionContext context, AiEntitlement.Source source, List<ModelAccessCandidate> candidates) {
        tx.requireWritable();
        Scope scope = scope(context);
        // 即使旧的 RR 快照没有迁移行，也必须先锁真实父范围，再用当前读判断切换。
        locks.parents(context.authorization().principal().subjectId(), List.of(scope.tenantId()));
        if (locks.lockTenant(scope.tenantId()).isEmpty()) {
            return new Decision(false, Map.of());
        }
        if (locks.lockWorkspace(scope).isEmpty()) {
            return new Decision(false, Map.of());
        }
        Optional<ModelAclScope> manifest = scopes.findCurrent(scope);
        return manifest.map(modelAclScope -> decide(actor(context, true), modelAclScope, source, List.copyOf(candidates), true))
                .orElseGet(() -> new Decision(false, Map.of()));
    }

    /**
     * 根据已存在的空间迁移清单，统一判定本次候选模型的公共 ACL 使用资格。
     * <p>
     * 先核对运行配置来源与清单一致、候选数量不超过 256，并检查空间 workspace:ai:use。
     * 再拒绝重复的精确 bindingRef，跳过未列入清单的候选；对清单内候选读取精确版本映射和管理资源，
     * 核验模型主空间、绑定族及不可变发布内容，防止通过相同引用替换模型、连接或费率。
     * 随后按每组最多 100 项组织 model:use 请求，并为允许项记录资源、归属、公共授权版本、
     * 清单版本和原定义来源，形成仅供本次使用的资格证明。
     * <p>
     * 展示路径使用普通事实读取及批量授权：空间普通拒绝返回空集合，单个模型普通拒绝仅过滤该项；
     * 授权权威不可用、映射缺失或内容不一致等错误直接传播，不解释为无模型或未迁移。
     * 当前事务路径使用当前事实读取及逐项强制授权，任一权限拒绝即抛出异常，阻止继续写入。
     * 本方法不创建事务；current 为 false 时由调用方提供只读快照，true 时须已在原可写事务内
     * 取得主体及父租户／空间治理门控。返回的 migrated 始终为 true，空允许集合也不能回退旧 Grant。
     * 资格证明不授予预算或凭据读取权限，也不能替代下一次执行边界的重新授权。
     *
     * @param actor      调用边界已核验身份及有效状态的可信操作者
     * @param manifest   与本次执行空间对应的已存在迁移清单，当前事务路径须使用当前读取结果
     * @param source     本次候选所属的唯一运行配置来源，必须与清单来源一致
     * @param candidates 同次可信配置中组装、尚未按模型 ACL 过滤的候选列表
     * @param current    是否在调用方原写事务及治理门控内读取当前事实并强制授权
     * @return 已迁移判定及按精确绑定引用索引的允许证明；允许集合可为空
     * @throws PlatformException 无法确认来源、候选、映射或授权权威，或当前事务授权被拒绝
     */
    private Decision decide(TrustedActor actor, ModelAclScope manifest, AiEntitlement.Source source, List<ModelAccessCandidate> candidates, boolean current) {
        if (manifest.configurationSource() != source || candidates.size() > 256) {
            throw unavailable();
        }
        AccessRequest workspace = DefaultAuthorizationService.workspaceRequest(manifest.scope(), WORKSPACE_AI_USE, "model-execution");
        if (current) {
            authorization.requireInCurrentTransaction(actor, workspace);
        } else {
            AccessDecision scopeAccess = authorization.check(actor, workspace);
            if (scopeAccess.reason() == AccessReasonEnum.AUTHORITY_UNAVAILABLE) {
                throw unavailable();
            }
            if (!scopeAccess.allowed()) {
                return new Decision(true, Map.of());
            }
        }
        Map<DefinitionRef, ModelAccessProof> allowed = new LinkedHashMap<>();
        List<ModelResourceVersion> versions = new ArrayList<>();
        List<ModelResource> resources = new ArrayList<>();
        Set<DefinitionRef> seen = new HashSet<>();
        for (ModelAccessCandidate candidate : candidates) {
            DefinitionRef ref = candidate.binding().definition();
            if (!seen.add(ref)) {
                throw unavailable();
            }
            if (!manifest.bindings().contains(ref)) {
                continue;
            }
            ModelResourceVersion version = current ? models.findCurrentBinding(manifest.scope().tenantId(), ref).orElseThrow(PlatformModelAclAccess::unavailable)
                    : models.findBinding(manifest.scope().tenantId(), ref).orElseThrow(PlatformModelAclAccess::unavailable);
            ModelResource resource = current ? models.findCurrent(version.resource()).orElseThrow(PlatformModelAclAccess::unavailable)
                    : models.find(version.resource()).orElseThrow(PlatformModelAclAccess::unavailable);
            validate(manifest.scope(), candidate, version, resource);
            versions.add(version);
            resources.add(resource);
        }
        for (int start = 0; start < versions.size(); start += 100) {
            int end = Math.min(start + 100, versions.size());
            List<AccessRequest> requests = resources.subList(start, end).stream()
                    .map(resource -> new AccessRequest(manifest.scope(), resource.resource(), MODEL_USE, "model-execution")).toList();
            List<AccessDecision> decisions = current ? List.of() : authorization.batchCheck(actor, requests);
            if (!current && decisions.size() != requests.size()) {
                throw unavailable();
            }
            for (int i = start; i < end; i++) {
                AuthorizationVersions basis;
                if (current) {
                    basis = authorization.requireInCurrentTransaction(actor, requests.get(i - start)).versions();
                } else {
                    AccessDecision decision = decisions.get(i - start);
                    if (decision.reason() == AccessReasonEnum.AUTHORITY_UNAVAILABLE) {
                        throw unavailable();
                    }
                    if (!decision.allowed()) {
                        continue;
                    }
                    basis = decision.versions();
                }
                ModelResourceVersion version = versions.get(i);
                ModelResource resource = resources.get(i);
                allowed.put(version.publication().binding().definition(), new ModelAccessProof(resource.resource(), resource.ownership(),
                        basis, manifest.version(), version.publication().source()));
            }
        }
        return new Decision(true, allowed);
    }

    static void validate(Scope scope, ModelAccessCandidate candidate, ModelResourceVersion version, ModelResource resource) {
        if (!version.resource().equals(resource.resource()) || !scope.tenantId().equals(resource.ownership().tenantId())
                || !scope.workspaceId().equals(resource.ownership().workspaceId())
                || !candidate.binding().definition().equals(version.publication().binding().definition())
                || !Objects.equals(resource.bindingId(), candidate.binding().definition().id())
                || !candidate.publication(version.publication()).contentDigest().equals(version.publication().contentDigest())) {
            throw unavailable();
        }
    }

    private TrustedActor actor(ExecutionContext context, boolean current) {
        ExecutionPrincipal expected = context.authorization().principal();
        Principal principal = (current ? identity.findCurrentBySubjectId(expected.subjectId()) : identity.findBySubjectId(expected.subjectId()))
                .filter(expected::sameIdentityAs).filter(value -> value.status() == PrincipalStatusEnum.ACTIVE)
                .orElseThrow(PlatformModelAclAccess::unavailable);
        return new TrustedActor(principal, clock.instant());
    }

    private static Scope scope(ExecutionContext context) {
        return new Scope(context.authorization().tenantId(), context.authorization().workspaceId());
    }

    static PlatformException unavailable() {
        return new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, "model-acl");
    }
}
