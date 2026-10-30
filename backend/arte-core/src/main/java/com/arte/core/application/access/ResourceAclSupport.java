package com.arte.core.application.access;

import com.arte.core.application.workspace.WorkspaceAccessSupport;
import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.enums.access.GrantStateEnum;
import com.arte.core.enums.access.ResourceVisibilityEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.workspace.WorkspaceStateEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.*;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.PolicyRules;
import com.arte.core.spi.access.DomainAccessConditionProvider;
import com.arte.core.spi.access.ResourceDescriptorProvider;
import com.arte.core.spi.access.ResourceGrantStore;

import java.time.Clock;
import java.util.*;

import static com.arte.core.constant.platform.PlatformActions.*;

/**
 * 公共 ACL 算法共用事实
 * <p>
 * 所有领域事实来自受信提供者，不推算创建者权限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 17:50 ✾
 */
final class ResourceAclSupport {

    static final String VISIBILITY_SOURCE = "visibility:";
    private final ResourceGrantStore store;
    private final List<ResourceDescriptorProvider> descriptors;
    private final List<DomainAccessConditionProvider> conditions;
    private final Clock clock;

    /**
     * 组合 ACL 存储、受信领域提供者及业务时钟，供统一授权和授权管理服务复用。
     *
     * @param store       公共 ACL 根及授权记录存储。
     * @param descriptors 按资源类型提供原始归属、状态及版本的受信提供者。
     * @param conditions  按资源类型和动作检查领域附加条件的受信提供者。
     * @param clock       授权期限判断及结果检查时间使用的业务时钟。
     */
    ResourceAclSupport(ResourceGrantStore store, List<ResourceDescriptorProvider> descriptors,
                       List<DomainAccessConditionProvider> conditions, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.descriptors = List.copyOf(descriptors);
        this.conditions = List.copyOf(conditions);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 通过唯一匹配的受信提供者读取领域描述，并核对返回的资源身份。
     * 提供者缺失或冲突时拒绝，不能从创建者或 ACL 镜像推算领域事实。
     *
     * @param ref     待读取的完整资源引用。
     * @param current 是否使用调用方原写事务内的当前读取；为 true 时须已取得对应治理门控。
     * @return 与请求资源身份一致的领域描述；不存在或无法确认时抛出异常。
     */
    ResourceDescriptor descriptor(ResourceRef ref, boolean current) {
        List<ResourceDescriptorProvider> providers = descriptors.stream().filter(p -> p.supports(ref.type())).toList();
        if (providers.size() != 1) {
            throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        }
        ResourceDescriptor value = (current ? providers.getFirst().findCurrent(ref) : providers.getFirst().find(ref))
                .orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        if (!value.resource().equals(ref)) {
            throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        }
        return value;
    }

    /**
     * 读取资源 ACL 根，校验其资源身份、归属与领域事实一致，且属于当前允许的使用范围。
     *
     * @param descriptor 受信领域描述，作为真实资源身份和归属的依据。
     * @param scope      当前选择的租户及空间范围；空间资源须归属该空间。
     * @param current    是否在原写事务及治理门控下锁定读取 ACL 根。
     * @return 通过身份、归属及范围核验的 ACL 根；不一致或不可见时抛出异常。
     */
    ResourceAccess access(ResourceDescriptor descriptor, Scope scope, boolean current) {
        ResourceAccess value = (current ? store.lockAccess(descriptor.resource()) : store.findAccess(descriptor.resource()))
                .orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        if (!value.resource().equals(descriptor.resource()) || !value.ownership().equals(descriptor.ownership())) {
            throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        }
        if (!scope.tenantId().equals(value.ownership().tenantId()) || value.ownership().workspaceId() != null
                && !scope.workspaceId().equals(value.ownership().workspaceId())) {
            throw error(PlatformErrorCodeEnum.NOT_FOUND);
        }
        return value;
    }

    /**
     * 有界读取指定资源的全部授权记录，校验分页信息、资源身份及重复记录。
     * 最多读取 100 页、每页 100 条，包含撤销历史；超限或分页异常时拒绝，不返回截断结果。
     *
     * @param ref     待查询授权的完整资源引用。
     * @param current 是否在原写事务及治理门控下使用当前读取。
     * @return 完整的不可变授权记录列表，记录是否有效由后续匹配规则判断。
     */
    List<ResourceGrant> grants(ResourceRef ref, boolean current) {
        List<ResourceGrant> result = new ArrayList<ResourceGrant>();
        Set<String> seen = new HashSet<>();
        // 单次规则计算有明确上限；超限拒绝，不截断后假装没有授权。
        for (int page = 1; page <= 100; page++) {
            PageQuery query = new PageQuery(page, 100);
            PageResult<ResourceGrant> values = current ? store.findCurrentGrants(ref, query) : store.findGrants(ref, query);
            if (values.current() != page || values.size() != 100 || values.total() > 10000) {
                throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            }
            for (ResourceGrant grant : values.records()) {
                if (!grant.resource().equals(ref) || !seen.add(grant.id())) {
                    throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                }
                result.add(grant);
            }
            if ((long) page * 100 >= values.total()) {
                return List.copyOf(result);
            }
            if (values.records().size() != 100) {
                throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            }
        }
        throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
    }

    /**
     * 组合可信资源事实、公共政策、有效动作授权和领域附加条件，生成业务资源授权结论。
     * 仅在允许时附带范围、ACL 及领域资源版本，结果仍需在最终提交边界重新核验。
     *
     * @param actor    当前经过可信身份核验的操作者。
     * @param request  本次使用范围、资源引用及待检查动作。
     * @param snapshot 已核验主体、成员资格及政策的空间事实快照。
     * @param current  是否使用原写事务内的当前事实进行最终校验。
     * @return 包含允许或拒绝、原因及适用版本信息的授权结论。
     */
    AccessDecision check(TrustedActor actor, AccessRequest request, WorkspaceAccessSupport.WorkspaceSnapshot snapshot,
                         boolean current) {
        ResourceDescriptor descriptor = descriptor(request.resource(), current);
        ResourceAccess access = access(descriptor, request.scope().requireWorkspace(), current);
        AccessReasonEnum reason = policy(request.action(), snapshot);
        if (reason == AccessReasonEnum.ALLOWED) {
            boolean allowed = grants(request.resource(), current).stream()
                    .anyMatch(g -> g.action().equals(request.action()) && matches(g, access, snapshot));
            reason = allowed ? domain(actor, request, descriptor, snapshot, current) : AccessReasonEnum.ACTION_NOT_GRANTED;
        }
        AuthorizationVersions v = snapshot.verified().versions();
        return new AccessDecision(reason == AccessReasonEnum.ALLOWED, reason,
                reason == AccessReasonEnum.ALLOWED ? new AuthorizationVersions(v.tenantEpoch(), v.workspaceEpoch(),
                        v.tenantMembershipGeneration(), v.tenantMembershipVersion(), v.workspaceMembershipGeneration(),
                        v.workspaceMembershipVersion(), access.aclVersion(), descriptor.version()) : null, clock.instant());
    }

    /**
     * 判断单条授权是否仍有效，并匹配当前主体的成员代次或空间角色／共享范围。
     * 主体授权绑定对应成员代次；角色及空间授权仅在 SPACE_SHARED 模式下参与匹配。
     *
     * @param grant    待检查的单条授权记录。
     * @param access   资源 ACL 根，提供归属及访问模式。
     * @param snapshot 当前主体及其有效成员关系的空间事实。
     * @return 授权状态、期限、模式及目标均满足时为 true；动作匹配由调用方另行检查。
     */
    private boolean matches(ResourceGrant grant, ResourceAccess access, WorkspaceAccessSupport.WorkspaceSnapshot snapshot) {
        if (grant.state() != GrantStateEnum.ACTIVE || grant.expiresAt() != null && !clock.instant().isBefore(grant.expiresAt())) {
            return false;
        }
        if (grant.sourceOperationId().startsWith(VISIBILITY_SOURCE) && access.visibility() != ResourceVisibilityEnum.SPACE_SHARED) {
            return false;
        }
        Scope scope = snapshot.workspace().scope();
        return switch (grant.target()) {
            case GrantTarget.Subject target -> target.subjectId().equals(snapshot.verified().principal().subjectId())
                    && target.scope().tenantId().equals(scope.tenantId())
                    && (access.ownership().workspaceId() == null
                    ? target.scope().workspaceId() == null && target.membershipGeneration() == snapshot.tenant().member().generation()
                    : scope.workspaceId().equals(target.scope().workspaceId()) && target.membershipGeneration() == snapshot.member().generation());
            case GrantTarget.SpaceRole target -> access.visibility() == ResourceVisibilityEnum.SPACE_SHARED
                    && target.scope().equals(scope) && target.role() == snapshot.member().role();
            case GrantTarget.Space target ->
                    access.visibility() == ResourceVisibilityEnum.SPACE_SHARED && target.scope().equals(scope);
        };
    }

    /**
     * 通过唯一匹配的领域条件提供者检查附加限制；仅在公共政策及动作授权通过后调用。
     * 提供者缺失或冲突时拒绝，领域允许结果不能覆盖公共拒绝。
     *
     * @param actor      当前经过可信身份核验的操作者。
     * @param request    本次资源动作检查请求。
     * @param descriptor 已核对身份和归属的受信领域描述。
     * @param snapshot   已验证的当前使用空间事实。
     * @param current    是否调用原写事务内的当前领域条件检查。
     * @return 领域条件的允许或拒绝原因，提供者不可确定时返回 AUTHORITY_UNAVAILABLE。
     */
    private AccessReasonEnum domain(TrustedActor actor, AccessRequest request, ResourceDescriptor descriptor,
                                    WorkspaceAccessSupport.WorkspaceSnapshot snapshot, boolean current) {
        List<DomainAccessConditionProvider> providers = conditions.stream().filter(p -> p.supports(request.resource().type(), request.action())).toList();
        if (providers.size() != 1) {
            return AccessReasonEnum.AUTHORITY_UNAVAILABLE;
        }
        return Objects.requireNonNull(current
                ? providers.getFirst().evaluateCurrent(actor, request, descriptor, snapshot.verified())
                : providers.getFirst().evaluate(actor, request, descriptor, snapshot.verified()));
    }

    /**
     * 检查空间归档状态、文章导出／外发政策及访客模型使用限制，租户和空间政策均须允许。
     * 本方法只判断公共限制，具体动作授权及领域条件由后续步骤检查。
     *
     * @param action   待检查的资源动作。
     * @param snapshot 当前空间状态、成员角色及两级政策事实。
     * @return 公共限制满足时返回 ALLOWED，否则返回相应的生命周期或政策拒绝原因。
     */
    private AccessReasonEnum policy(Action action, WorkspaceAccessSupport.WorkspaceSnapshot snapshot) {
        if (snapshot.workspace().state() == WorkspaceStateEnum.ARCHIVED && !action.value().endsWith(":read")) {
            return AccessReasonEnum.LIFECYCLE_DENIED;
        }
        PolicyRules tenant = snapshot.tenant().policy().rules();
        PolicyRules workspace = snapshot.policy().rules();
        if (action.equals(ARTICLE_EXPORT) && (!tenant.exportTypes().contains(com.arte.core.constant.platform.PlatformResourceTypes.ARTICLE)
                || !workspace.exportTypes().contains(com.arte.core.constant.platform.PlatformResourceTypes.ARTICLE))) {
            return AccessReasonEnum.POLICY_DENIED;
        }
        if (action.equals(ARTICLE_EGRESS) && (!tenant.egressTypes().contains(com.arte.core.constant.platform.PlatformResourceTypes.ARTICLE)
                || !workspace.egressTypes().contains(com.arte.core.constant.platform.PlatformResourceTypes.ARTICLE))) {
            return AccessReasonEnum.POLICY_DENIED;
        }
        if (Set.of(MODEL_USE, MODEL_VALIDATE).contains(action)
                && snapshot.member().role() == com.arte.core.enums.workspace.WorkspaceRoleEnum.GUEST
                && (!tenant.guestAiAllowed() || !workspace.guestAiAllowed())) {
            return AccessReasonEnum.POLICY_DENIED;
        }
        return AccessReasonEnum.ALLOWED;
    }

    /**
     * 构造带有 resource-acl 固定标识的公共异常，供 ACL 判定及管理流程统一使用。
     *
     * @param code 本次失败对应的公共错误码。
     * @return 尚未抛出的公共平台异常。
     */
    static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "resource-acl");
    }
}
