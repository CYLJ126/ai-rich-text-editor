package com.arte.core.application.access;

import com.arte.core.api.access.ResourceAccessService;
import com.arte.core.api.access.ResourceRegistrationService;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.application.platform.PlatformTimes;
import com.arte.core.application.workspace.WorkspaceAccessSupport;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.application.workspace.WorkspaceMutationEffects;
import com.arte.core.enums.access.GrantSourceEnum;
import com.arte.core.enums.access.GrantStateEnum;
import com.arte.core.enums.access.ResourceVisibilityEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.pojo.access.*;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.Tenant;
import com.arte.core.pojo.workspace.TenantMembership;
import com.arte.core.pojo.workspace.Workspace;
import com.arte.core.pojo.workspace.WorkspaceMembership;
import com.arte.core.spi.access.ActionRegistry;
import com.arte.core.spi.access.DomainAccessConditionProvider;
import com.arte.core.spi.access.ResourceDescriptorProvider;
import com.arte.core.spi.access.ResourceGrantStore;
import com.arte.core.spi.platform.IdentityProvider;
import com.arte.core.spi.platform.PlatformIdGenerator;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.TenantStore;
import com.arte.core.spi.workspace.WorkspaceStore;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

import static com.arte.core.application.access.ResourceAclSupport.error;
import static com.arte.core.constant.platform.PlatformActions.*;
import static com.arte.core.constant.platform.PlatformResourceTypes.*;

/**
 * 公共资源 ACL 登记与授权管理的默认应用服务。
 * <p>
 * 负责资源创建时的初始授权、授权记录查询、动作授予与撤销，以及资源共享模式变更。
 * 复用统一权限判定和数据库治理门控，将 ACL、授权代次、幂等回执、审计及事件纳入同一本地事务。
 * 首批支持空间资源登记，不暴露注册 HTTP，也不授予凭据或预算资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 17:50 ✾
 */
public final class DefaultResourceAccessService implements ResourceAccessService, ResourceRegistrationService {

    private final ResourceGrantStore store;
    private final ResourceAclSupport acl;
    private final WorkspaceAccessSupport access;
    private final DefaultAuthorizationService authorization;
    private final WorkspaceGovernanceLocks locks;
    private final DefaultCommandExecutor commands;
    private final WorkspaceMutationEffects effects;
    private final PublicTransactionRunner tx;
    private final TenantStore tenants;
    private final WorkspaceStore workspaces;
    private final IdentityProvider identities;
    private final PlatformIdGenerator ids;
    private final Clock clock;
    private final ActionRegistry actions = DefaultActionRegistry.standard();

    public DefaultResourceAccessService(ResourceGrantStore store, List<ResourceDescriptorProvider> descriptors, List<DomainAccessConditionProvider> conditions, WorkspaceAccessSupport access, DefaultAuthorizationService authorization, WorkspaceGovernanceLocks locks, DefaultCommandExecutor commands, WorkspaceMutationEffects effects, PublicTransactionRunner tx, TenantStore tenants, WorkspaceStore workspaces, IdentityProvider identities, PlatformIdGenerator ids, Clock clock) {
        this.store = store;
        this.acl = new ResourceAclSupport(store, descriptors, conditions, clock);
        this.access = access;
        this.authorization = authorization;
        this.locks = locks;
        this.commands = commands;
        this.effects = effects;
        this.tx = tx;
        this.tenants = tenants;
        this.workspaces = workspaces;
        this.identities = identities;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public ResourceAccess registerCreated(TrustedActor actor, ResourceDescriptor descriptor, CommandMeta meta) {
        tx.requireWritable();
        return tx.required(() -> registerInTransaction(actor, descriptor, meta));
    }

    @Override
    public ResourceAccessViews.AccessView getAccess(TrustedActor actor, Scope scope, ResourceRef ref) {
        return tx.required(() -> {
            lock(actor, scope, null);
            require(actor, scope, ref, ref.type().equals(BUDGET_ACCOUNT) ? BUDGET_ACCOUNT_ASSIGN_RECONCILER : RESOURCE_ACCESS_MANAGE);
            return view(current(scope, ref));
        });
    }

    @Override
    public PageResult<ResourceAccessViews.GrantView> listGrants(TrustedActor actor, Scope scope, ResourceRef ref, PageQuery query) {
        return tx.required(() -> {
            lock(actor, scope, null);
            require(actor, scope, ref, ref.type().equals(BUDGET_ACCOUNT) ? BUDGET_ACCOUNT_ASSIGN_RECONCILER : RESOURCE_ACCESS_MANAGE);
            current(scope, ref);
            PageResult<ResourceGrant> page = store.findCurrentGrants(ref, query);
            if (page.records().stream().anyMatch(g -> !g.resource().equals(ref))) {
                throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            }
            return new PageResult<>(page.current(), page.size(), page.total(), page.records().stream().map(DefaultResourceAccessService::view).toList());
        });
    }

    /**
     * 向指定目标授予一组资源动作，每个动作独立保存一条管理或分享来源的授权记录。
     * <p>
     * 取得治理门控后，校验操作者的 ACL 管理或分享资格、待授动作、当前 ACL 版本、目标成员资格、分享政策及期限。
     * 在同一事务内写入授权、推进 ACL 版本和所属范围的授权纪元，并记录事件、审计及幂等回执。
     * 同键重放重新核验当前资格并返回原结果，不重复授予。
     *
     * @param actor   发起授权的可信操作者，必须持有全部待授予动作及相应管理或分享资格。
     * @param command 授权命令，包含资源范围、接收目标、动作集合、期限、预期 ACL 版本及幂等元信息。
     * @return 包含逐项授权视图及操作后 ACL 版本的成功回执，或当前仍有资格获取的原成功回执。
     */
    @Override
    public CommandReceipt<ResourceAccessViews.GrantResult> grant(TrustedActor actor, ResourceAccessCommands.GrantResource command) {
        // 整合业务字段，用于计算摘要
        List<String> fields = fields(command.scope(), command.resource(), command.expectedAclVersion());
        fields.addAll(recipientFields(command.recipient()));
        addActions(fields, command.actions());
        fields.add(string(command.expiresAt()));
        return commands.execute(actor, command.meta(), commandScope(command.scope()), "resource:grant", "resource:grant:v1",
                ResourceAccessViews.GrantResult.class, fields, () -> lock(actor, command.scope(), command.recipient()),
                value -> {
                    requireManagerOrShare(actor, command.scope(), command.resource(), command.actions());
                    // 确认重放结果中的全部授权仍属于本次请求的资源，拒绝错配回执。
                    if (value.grants().stream().anyMatch(g -> !g.resource().equals(command.resource()))) {
                        throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                    }
                }, operation -> {
                    boolean manager = requireManagerOrShare(actor, command.scope(), command.resource(), command.actions());
                    // 读取当前资源 ACL，并校验命令携带的预期版本，防止并发覆盖。
                    ResourceAccess old = current(command.scope(), command.resource());
                    check(old.aclVersion() == command.expectedAclVersion());
                    // 校验接收目标并由服务端绑定当前成员代次。
                    GrantTarget target = recipient(command.scope(), old, command.recipient());
                    // 检查分享目标种类的政策上限，并拒绝已到期的授权期限。
                    requireSharePolicy(actor, command.scope(), target);
                    expiry(command.expiresAt());
                    if (command.resource().type().equals(BUDGET_ACCOUNT)) {
                        if (!(command.recipient() instanceof GrantRecipient.Subject))
                            throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
                        // 具体账户只设一位主责任人；接替与新授权在同一 CAS 事务保留撤销历史。
                        if (command.actions().contains(BUDGET_ACCOUNT_RECONCILE))
                            for (ResourceGrant grant : acl.grants(command.resource(), true))
                                if (grant.action().equals(BUDGET_ACCOUNT_RECONCILE) && grant.state() == GrantStateEnum.ACTIVE)
                                    revokeRecord(grant);
                    }
                    ArrayList<ResourceAccessViews.GrantView> result = new ArrayList<>();
                    for (Action action : command.actions().stream().sorted(Comparator.comparing(Action::value)).toList()) {
                        // 插入数据库，为每个动作分别保存管理或分享来源的授权，保留独立来源。
                        result.add(view(insert(old.resource(), target, action, command.expiresAt(), manager ? GrantSourceEnum.MANAGEMENT : GrantSourceEnum.SHARE, operation, actor)));
                    }
                    // 保持访问模式并推进 ACL 版本，通过 CAS 保存授权变更。
                    ResourceAccess next = advance(old, old.visibility());
                    check(store.replaceAccess(next, old.aclVersion()));
                    // 同步推进归属范围的授权代次，并记录审计和 Outbox 事件。
                    bump(actor, command.scope(), next, command.resource().type().equals(BUDGET_ACCOUNT)
                            ? command.actions().contains(BUDGET_ACCOUNT_RECONCILE) ? "budget-account:reconciler-assigned" : "budget-account:governor-granted" : "resource:granted", operation);
                    // 将逐项授权结果及新的 ACL 版本写入命令回执。
                    return new DefaultCommandExecutor.Result<>(new ResourceAccessViews.GrantResult(result, next.aclVersion()), next.aclVersion());
                });
    }

    /**
     * 撤销指定资源的一条有效授权，保留该记录及其他独立来源的授权。
     * <p>
     * 在治理门控下检查撤销资格、当前 ACL 版本及授权记录版本，将目标记录改为 REVOKED 并递增其版本。
     * 同一事务内推进 ACL 版本和所属范围的授权纪元，记录事件、审计及幂等回执。
     * 同键重放重新检查当前撤销资格，不重复修改授权或生成事件。
     *
     * @param actor   发起撤销的可信操作者，须通过 ACL 管理、空间资源治理或本人分享撤销资格校验。
     * @param command 撤销命令，包含资源范围、授权记录 ID、预期 ACL 与授权版本及幂等元信息。
     * @return 业务值为 null 的成功回执，结果版本为操作后的 ACL 版本；幂等重放保留原回执信息。
     */
    @Override
    public CommandReceipt<Void> revoke(TrustedActor actor, ResourceAccessCommands.RevokeGrant command) {
        List<String> fields = fields(command.scope(), command.resource(), command.expectedAclVersion());
        fields.add(command.grantId());
        fields.add(Long.toString(command.expectedGrantVersion()));
        return commands.execute(actor, command.meta(), commandScope(command.scope()), "resource:revoke", "resource:void:v1",
                Void.class, fields, () -> lock(actor, command.scope(), null), ignored -> revokePermission(actor, command),
                operation -> {
                    revokePermission(actor, command);
                    ResourceAccess old = current(command.scope(), command.resource());
                    check(old.aclVersion() == command.expectedAclVersion());
                    ResourceGrant grant = store.findCurrentGrant(command.resource(), command.grantId()).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
                    check(grant.version() == command.expectedGrantVersion() && grant.state() == GrantStateEnum.ACTIVE);
                    revokeRecord(grant);
                    ResourceAccess next = advance(old, old.visibility());
                    check(store.replaceAccess(next, old.aclVersion()));
                    bump(actor, command.scope(), next, command.resource().type().equals(BUDGET_ACCOUNT)
                            ? grant.action().equals(BUDGET_ACCOUNT_RECONCILE) ? "budget-account:responsibility-revoked" : "budget-account:governor-revoked" : "resource:revoked", operation);
                    return new DefaultCommandExecutor.Result<Void>(null, next.aclVersion());
                });
    }

    /**
     * 切换资源访问模式，并替换由共享模式维护的角色或全空间动作授权。
     * <p>
     * 在治理门控下校验 ACL 管理资格及版本，先完整核验新规则的目标、政策、期限、动作资格和重复项。
     * 通过后撤销原共享模式授权、写入新规则，并通过 CAS 更新访问模式和 ACL 版本；其他独立来源的授权保留。
     * 所属范围授权纪元、事件、审计及回执同事务提交；同键重放校验当前管理资格后返回原结果。
     *
     * @param actor   发起访问模式变更的可信操作者，必须具有该资源的 ACL 管理资格。
     * @param command 模式变更命令，包含资源范围、新访问模式、明确的共享规则、预期 ACL 版本及幂等元信息。
     * @return 包含操作后资源归属、访问模式及 ACL 版本的成功回执，或通过当前资格校验的原成功回执。
     */
    @Override
    public CommandReceipt<ResourceAccessViews.AccessView> changeVisibility(TrustedActor actor, ResourceAccessCommands.ChangeVisibility command) {
        if (command.resource().type().equals(BUDGET_ACCOUNT)) throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
        List<String> fields = fields(command.scope(), command.resource(), command.expectedAclVersion());
        fields.add(command.visibility().name());
        // 列表次序不是语义；排序后的每项仍按长度前缀展开，保留重复项用于明确拒绝。
        command.sharedGrants().stream().sorted(Comparator.comparing(g -> DefaultCommandExecutor.digest(visibilityFields(g))))
                .forEach(g -> fields.addAll(visibilityFields(g)));
        return commands.execute(actor, command.meta(), commandScope(command.scope()), "resource:visibility", "resource:access:v1",
                ResourceAccessViews.AccessView.class, fields, () -> lock(actor, command.scope(), null),
                value -> {
                    // 确认重放的访问配置对应本次请求的资源。
                    if (!value.resource().equals(command.resource())) {
                        throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                    }
                    // 重放前重新检查操作者当前是否仍有该资源的 ACL 管理资格。
                    require(actor, command.scope(), command.resource(), RESOURCE_ACCESS_MANAGE);
                }, operation -> {
                    require(actor, command.scope(), command.resource(), RESOURCE_ACCESS_MANAGE);
                    ResourceAccess old = current(command.scope(), command.resource());
                    check(old.aclVersion() == command.expectedAclVersion());
                    Set<String> unique = new HashSet<>();
                    List<ResourceGrant> pending = new ArrayList<>();
                    // 先完整校验新共享规则并构建待写记录，通过后再修改现有授权。
                    for (VisibilityGrant shared : command.sharedGrants()) {
                        GrantTarget target = recipient(command.scope(), old, shared.recipient());
                        requireSharePolicy(actor, command.scope(), target);
                        expiry(shared.expiresAt());
                        // 逐个检查共享规则中明确指定的资源动作。
                        for (Action action : shared.actions()) {
                            ActionDefinition definition = actions.find(action).orElseThrow(() -> error(PlatformErrorCodeEnum.UNKNOWN_ACTION));
                            // 共享动作必须适用于当前资源类型，且允许通过分享委派。
                            if (!definition.resourceTypes().contains(old.resource().type()) || !definition.shareDelegable()) {
                                throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
                            }
                            // 确认操作者本人当前持有该动作，避免分享时扩大权限。
                            require(actor, command.scope(), old.resource(), action);
                            // 拒绝同一目标和动作的重复共享项，避免产生重复规则。
                            if (!unique.add(DefaultCommandExecutor.digest(concat(recipientFields(shared.recipient()), List.of(action.value()))))) {
                                throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
                            }
                            pending.add(new ResourceGrant(ids.nextId(), old.resource(), target, action, GrantStateEnum.ACTIVE, shared.expiresAt(), GrantSourceEnum.SHARE, ResourceAclSupport.VISIBILITY_SOURCE + operation, actor.principal().subjectId(), PlatformTimes.businessNow(clock), 0));
                        }
                    }
                    for (ResourceGrant grant : acl.grants(old.resource(), true)) {
                        // 仅撤销共享模式维护的有效授权，保留其他独立来源记录。
                        if (grant.source() == GrantSourceEnum.SHARE && grant.sourceOperationId().startsWith(ResourceAclSupport.VISIBILITY_SOURCE) && grant.state() == GrantStateEnum.ACTIVE) {
                            revokeRecord(grant);
                        }
                    }
                    // 写入全部已校验的新共享授权，唯一键冲突时回滚本次操作。
                    for (ResourceGrant grant : pending) {
                        check(store.insertGrantIfAbsent(grant));
                    }
                    ResourceAccess next = advance(old, command.visibility());
                    check(store.replaceAccess(next, old.aclVersion()));
                    bump(actor, command.scope(), next, "resource:visibility:changed", operation);
                    return new DefaultCommandExecutor.Result<>(view(next), next.aclVersion());
                });
    }

    private void lock(TrustedActor actor, Scope scope, GrantRecipient recipient) {
        SortedSet<String> subjects = new TreeSet<>();
        subjects.add(actor.principal().subjectId());
        // 直接主体目标也加入有序主体门控，避免后续读取成员时逆序取锁。
        if (recipient instanceof GrantRecipient.Subject(String subjectId)) {
            subjects.add(subjectId);
        }
        // 目标主体门控也在父租户之前取得，不能在持有租户锁后临时扩展主体锁。
        subjects.forEach(locks::subject);
        // 取得父租户、目标空间及操作者成员门控，保护后续当前授权检查。
        locks.scopes(actor.principal().subjectId(), List.of(scope));
    }

    private void require(TrustedActor actor, Scope scope, ResourceRef ref, Action action) {
        // 在调用方原事务中按当前事实强制校验指定资源动作。
        authorization.requireInCurrentTransaction(actor, new AccessRequest(scope, ref, action, "resource-acl"));
    }

    private ResourceAccess current(Scope scope, ResourceRef ref) {
        // 读取当前领域描述与 ACL，确认真实归属及请求范围一致。
        return acl.access(acl.descriptor(ref, true), scope, true);
    }

    /**
     * 在资源创建的原写事务中登记空间资源 ACL，并为创建者保存显式初始授权。
     * <p>
     * 取得治理门控后核对创建主体、领域当前描述及父空间创建资格，建立 EXPLICIT 模式的 ACL 根，
     * 将创建者的初始动作逐项保存为绑定当前成员代次的 CREATION 来源授权。
     * 所属空间授权纪元、事件、审计和回执与登记同事务提交；同键重放核对原资源及当前 ACL 管理资格后返回原结果。
     *
     * @param actor      创建资源的可信操作者，须与领域描述中的创建主体一致。
     * @param descriptor 受信领域提供的资源身份、空间归属、状态、版本及创建主体；首批不接受租户级资源登记。
     * @param meta       登记命令的幂等操作键及追踪信息。
     * @return 本次登记或幂等重放返回的 ACL 根，包含原结果中的归属、访问模式及 ACL 版本。
     */
    private ResourceAccess registerInTransaction(TrustedActor actor, ResourceDescriptor descriptor, CommandMeta meta) {
        if (descriptor.ownership().workspaceId() == null) {
            // 租户级创建需后续专门治理命令
            throw error(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED);
        }
        Scope scope = new Scope(descriptor.ownership().tenantId(), descriptor.ownership().workspaceId());
        return registerInScope(actor, descriptor, scope, meta);
    }

    @Override
    public ResourceAccess registerTenantCreated(TrustedActor actor, ResourceDescriptor descriptor, Scope scope, CommandMeta meta) {
        if (descriptor.ownership().workspaceId() != null || !descriptor.ownership().tenantId().equals(scope.tenantId())) {
            throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
        }
        return tx.required(() -> registerInScope(actor, descriptor, scope, meta));
    }

    private ResourceAccess registerInScope(TrustedActor actor, ResourceDescriptor descriptor, Scope scope, CommandMeta meta) {
        List<String> fields = fields(scope, descriptor.resource(), descriptor.version());
        fields.add(descriptor.createdBy());
        fields.add(descriptor.domainState());
        CommandReceipt<ResourceAccessViews.AccessView> receipt = commands.execute(actor, meta, commandScope(scope),
                "resource:register", "resource:access:v1", ResourceAccessViews.AccessView.class, fields,
                () -> lock(actor, scope, null),
                value -> {
                    // 确认登记重放结果的资源身份和归属与原请求一致。
                    if (!value.resource().equals(descriptor.resource()) || !value.ownership().equals(descriptor.ownership())) {
                        throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                    }
                    // 登记重放仍要求操作者当前具有该资源的 ACL 管理资格。
                    require(actor, scope, descriptor.resource(), RESOURCE_ACCESS_MANAGE);
                }, operation -> {
                    // 确认创建者就是当前主体，且传入描述与领域当前事实完全一致。
                    if (!descriptor.createdBy().equals(actor.principal().subjectId()) || !acl.descriptor(descriptor.resource(), true).equals(descriptor)) {
                        throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
                    }
                    Action create = creation(descriptor.resource().type());
                    if (descriptor.ownership().workspaceId() == null) {
                        authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.tenantRequest(scope.tenantId(),
                                TENANT_POLICY_WRITE, "tenant-resource-create"));
                    }
                    authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(scope, create, "resource-create"));
                    ResourceAccess root = new ResourceAccess(descriptor.resource(), descriptor.ownership(), ResourceVisibilityEnum.EXPLICIT, 0);
                    // 保存资源 ACL 根，即保存资源整体的访问配置。
                    check(store.insertAccess(root));
                    // 将创建者解析为绑定当前成员代次的直接授权目标。
                    GrantTarget target = recipient(scope, root, new GrantRecipient.Subject(actor.principal().subjectId()));
                    for (Action action : initial(descriptor.resource().type())) {
                        // 为创建者逐项保存 CREATION 来源的显式初始授权，即保存具体的动作授权。
                        insert(root.resource(), target, action, null, GrantSourceEnum.CREATION, operation, actor);
                    }
                    bump(actor, scope, root, "resource:registered", operation);
                    return new DefaultCommandExecutor.Result<>(view(root), root.aclVersion());
                });
        return new ResourceAccess(receipt.value().resource(), receipt.value().ownership(), receipt.value().visibility(), receipt.value().aclVersion());
    }

    /**
     * 在当前事务中校验操作者是否具备资源 ACL 管理或分享资格，并确认其持有所有待授予动作。
     * 普通分享者只能授予允许通过分享委派的动作；ACL 管理者同样不能授予自己未持有的动作。
     *
     * @return true 表示具有 ACL 管理资格，false 表示仅通过分享资格校验；校验不通过时抛出异常。
     */
    private boolean requireManagerOrShare(TrustedActor actor, Scope scope, ResourceRef ref, Set<Action> requested) {
        if (ref.type().equals(BUDGET_ACCOUNT)) {
            if (!Set.of(BUDGET_ACCOUNT_RECONCILE, BUDGET_ACCOUNT_ASSIGN_RECONCILER).containsAll(requested))
                throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
            require(actor, scope, ref, BUDGET_ACCOUNT_ASSIGN_RECONCILER);
            return true;
        }
        boolean manager = authorization.checkCurrent(actor, new AccessRequest(scope, ref, RESOURCE_ACCESS_MANAGE, "resource-acl")).allowed();
        // 没有 ACL 管理资格时，必须具有该资源的分享资格。
        if (!manager) {
            require(actor, scope, ref, share(ref.type()));
        }
        for (Action action : requested) {
            ActionDefinition definition = actions.find(action).orElseThrow(() -> error(PlatformErrorCodeEnum.UNKNOWN_ACTION));
            // 校验动作适用类型，并限制普通分享者只能授予允许委派的动作。
            if (!definition.resourceTypes().contains(ref.type()) || !manager && !definition.shareDelegable()) {
                throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
            }
            require(actor, scope, ref, action);
        }
        return manager;
    }

    /**
     * 校验操作者是否有权撤销指定授权，不执行实际撤销。
     * ACL 管理者或具备空间资源治理资格的操作者可撤销授权；普通分享者只能撤销本人创建的非共享模式 SHARE 记录。
     *
     * @param actor   当前经过可信身份核验的操作者。
     * @param command 撤销命令，提供资源、当前范围和待撤销授权记录 ID。
     */
    private void revokePermission(TrustedActor actor, ResourceAccessCommands.RevokeGrant command) {
        if (command.resource().type().equals(BUDGET_ACCOUNT)) {
            require(actor, command.scope(), command.resource(), BUDGET_ACCOUNT_ASSIGN_RECONCILER);
            return;
        }
        if (authorization.checkCurrent(actor, new AccessRequest(command.scope(), command.resource(), RESOURCE_ACCESS_MANAGE, "resource-acl")).allowed()) {
            return;
        }
        // 空间治理者可撤销异常授权，但此资格不能用于读取内容、列出私有 ACL 或给自己补授权。
        ResourceAccess root = current(command.scope(), command.resource());
        if (root.ownership().workspaceId() != null && authorization.checkCurrent(actor, DefaultAuthorizationService.workspaceRequest(command.scope(), WORKSPACE_RESOURCE_GOVERN, "resource-revoke")).allowed()) {
            return;
        }
        require(actor, command.scope(), command.resource(), share(command.resource().type()));
        ResourceGrant grant = store.findCurrentGrant(command.resource(), command.grantId()).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        if (grant.source() != GrantSourceEnum.SHARE || !grant.issuedBy().equals(actor.principal().subjectId()) || grant.sourceOperationId().startsWith(ResourceAclSupport.VISIBILITY_SOURCE)) {
            throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
        }
    }

    /**
     * 将期望授权目标解析为受信授权目标，校验目标主体、当前成员资格及空间范围。
     * 直接主体授权绑定对应租户或空间的成员代次；角色和空间目标仅支持当前空间。
     *
     * @param scope     当前操作的租户与空间范围。
     * @param root      当前资源 ACL 根，用于区分资源的租户归属和空间归属。
     * @param recipient 命令指定的期望授权目标，不包含客户端自报的成员代次。
     * @return 经校验的授权目标，直接主体目标包含服务端读取的当前成员代次。
     */
    private GrantTarget recipient(Scope scope, ResourceAccess root, GrantRecipient recipient) {
        return switch (recipient) {
            case GrantRecipient.Subject subject -> {
                Principal principal = identities.findCurrentBySubjectId(subject.subjectId()).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
                // 校验目标主体身份一致且当前有效，拒绝失效或错配主体。
                if (!principal.subjectId().equals(subject.subjectId()) || principal.status() != com.arte.core.enums.platform.PrincipalStatusEnum.ACTIVE) {
                    throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
                }
                TenantMembership tenant = locks.lockTenantMember(scope.tenantId(), subject.subjectId()).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
                // 核对租户成员记录的租户和主体，拒绝权威事实错配。
                if (!tenant.tenantId().equals(scope.tenantId()) || !tenant.subjectId().equals(subject.subjectId())) {
                    throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                }
                // 仅向当前有效的租户成员授予直接主体权限。
                if (tenant.state() != MembershipStateEnum.ACTIVE) {
                    throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
                }
                // 租户级资源的直接主体授权绑定当前租户成员代次。
                if (root.ownership().workspaceId() == null) {
                    yield new GrantTarget.Subject(subject.subjectId(), new CommandScope(scope.tenantId(), null), tenant.generation());
                }
                // 空间资源还需读取接收主体在当前空间内的成员资格。
                WorkspaceMembership member = locks.lockWorkspaceMember(scope, subject.subjectId()).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
                // 核对空间成员记录的完整范围和主体，防止跨范围错配。
                if (!member.scope().equals(scope) || !member.subjectId().equals(subject.subjectId())) {
                    throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                }
                // 仅向当前有效的空间成员授予直接主体权限。
                if (member.state() != MembershipStateEnum.ACTIVE) {
                    throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
                }
                yield new GrantTarget.Subject(subject.subjectId(), commandScope(scope), member.generation());
            }
            case GrantRecipient.SpaceRole role -> {
                // 角色共享首版限定在当前空间，拒绝跨空间目标。
                if (!role.scope().equals(scope)) {
                    throw error(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED);
                }
                yield new GrantTarget.SpaceRole(scope, role.role());
            }
            case GrantRecipient.Space space -> {
                // 全空间共享首版限定在当前空间，拒绝跨空间目标。
                if (!space.scope().equals(scope)) {
                    throw error(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED);
                }
                yield new GrantTarget.Space(scope);
            }
        };
    }

    /**
     * 校验授权目标种类是否同时满足当前租户和空间的分享政策，不满足时拒绝操作。
     *
     * @param actor  当前经过可信身份核验的操作者。
     * @param scope  当前操作的租户与空间范围。
     * @param target 已解析的授权目标，用于取得主体、角色或整个空间等目标种类。
     */
    private void requireSharePolicy(TrustedActor actor, Scope scope, GrantTarget target) {
        WorkspaceAccessSupport.WorkspaceSnapshot snapshot = access.snapshotCurrent(actor, scope);
        if (!snapshot.policy().rules().shareTargetKinds().contains(target.kind()) || !snapshot.tenant().policy().rules().shareTargetKinds().contains(target.kind())) {
            throw error(PlatformErrorCodeEnum.POLICY_DENIED);
        }
    }

    private ResourceGrant insert(ResourceRef ref, GrantTarget target, Action action, Instant expiry, GrantSourceEnum source, String operation, TrustedActor actor) {
        ResourceGrant grant = new ResourceGrant(ids.nextId(), ref, target, action, GrantStateEnum.ACTIVE, expiry, source,
                operation, actor.principal().subjectId(), PlatformTimes.businessNow(clock), 0);
        // 通过独立来源唯一键保存授权，冲突时拒绝重复插入。
        check(store.insertGrantIfAbsent(grant));
        return grant;
    }

    private void revokeRecord(ResourceGrant grant) {
        check(store.replaceGrant(new ResourceGrant(grant.id(), grant.resource(), grant.target(), grant.action(),
                GrantStateEnum.REVOKED, grant.expiresAt(), grant.source(), grant.sourceOperationId(), grant.issuedBy(),
                grant.createdAt(), Math.incrementExact(grant.version())), grant.version()));
    }

    /**
     * 在原事务中推进资源所属租户或空间的记录版本及授权纪元（authEpoch），并追加 Outbox 事件和操作者审计。
     * 租户级资源更新租户，空间资源更新所属空间；通过当前读取及 CAS 防止并发覆盖，本方法不修改资源 ACL 版本。
     *
     * @param actor     发起本次授权变更的可信操作者，用于记录审计主体。
     * @param scope     当前操作的租户与空间范围，调用方须已取得相应治理门控。
     * @param root      本次操作的 ACL 根，提供资源归属、资源引用及事件关联的 ACL 版本。
     * @param event     待记录的资源授权变更事件类型。
     * @param operation 本次命令的操作 ID，用于关联事件、审计和幂等回执。
     */
    private void bump(TrustedActor actor, Scope scope, ResourceAccess root, String event, String operation) {
        if (root.ownership().workspaceId() == null) {
            // 取得资源所属租户的当前门控记录，准备推进授权代次。
            Tenant t = locks.lockTenant(scope.tenantId()).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
            // 同步递增租户记录版本和授权代次，通过 CAS 保存并保持更新时间非递减。
            check(tenants.replace(new Tenant(t.id(), t.name(), t.type(), t.personalSubjectId(), t.state(), t.createdBy(),
                    Math.incrementExact(t.version()), Math.incrementExact(t.authEpoch()), t.createdAt(),
                    PlatformTimes.notBefore(clock, t.updatedAt())), t.version()));
        } else {
            // 取得资源所属空间的当前门控记录，准备推进记录版本和授权代次。
            Workspace w = locks.lockWorkspace(scope).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
            check(workspaces.replace(new Workspace(w.scope(), w.name(), w.description(), w.state(), w.createdBy(),
                    Math.incrementExact(w.version()), Math.incrementExact(w.authEpoch()), w.createdAt(),
                    PlatformTimes.notBefore(clock, w.updatedAt())), w.version()));
        }
        // 在同一事务内追加资源授权变更事件及操作者审计记录。
        effects.append(actor, commandScope(scope), event, operation, root.aclVersion(), Map.of("resourceType",
                root.resource().type().value(), "resourceId", root.resource().resourceId()));
    }

    /**
     * 保留资源身份及归属，生成使用指定访问模式、ACL 版本递增一的新根记录；不执行持久化。
     *
     * @param old        当前 ACL 根记录。
     * @param visibility 新记录采用的访问模式，也可沿用原模式。
     * @return ACL 版本递增一的新根记录，供调用方通过 CAS 保存。
     */
    private static ResourceAccess advance(ResourceAccess old, ResourceVisibilityEnum visibility) {
        return new ResourceAccess(old.resource(), old.ownership(), visibility, Math.incrementExact(old.aclVersion()));
    }

    private void expiry(Instant value) {
        if (value != null && !value.isAfter(clock.instant())) {
            throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
        }
    }

    private static void check(boolean ok) {
        if (!ok) {
            throw error(PlatformErrorCodeEnum.VERSION_CONFLICT);
        }
    }

    private static CommandScope commandScope(Scope s) {
        return new CommandScope(s.tenantId(), s.workspaceId());
    }

    private static String string(Instant value) {
        return value == null ? null : value.toString();
    }

    /**
     * 将 ACL 根转换为资源访问配置视图，不包含正文或某个主体的最终权限结论。
     *
     * @param a 待转换的资源 ACL 根记录。
     * @return 包含资源引用、归属、访问模式及 ACL 版本的视图。
     */
    private static ResourceAccessViews.AccessView view(ResourceAccess a) {
        return new ResourceAccessViews.AccessView(a.resource(), a.ownership(), a.visibility(), a.aclVersion());
    }

    /**
     * 将单条授权转换为管理视图，并将内部授权目标转换为不携带成员代次的展示目标。
     *
     * @param g 待转换的授权记录。
     * @return 包含授权目标、动作、状态、期限、来源及记录版本的视图。
     */
    private static ResourceAccessViews.GrantView view(ResourceGrant g) {
        GrantRecipient recipient = switch (g.target()) {
            case GrantTarget.Subject s -> new GrantRecipient.Subject(s.subjectId());
            case GrantTarget.SpaceRole s -> new GrantRecipient.SpaceRole(s.scope(), s.role());
            case GrantTarget.Space s -> new GrantRecipient.Space(s.scope());
        };
        return new ResourceAccessViews.GrantView(g.id(), g.resource(), recipient, g.action(), g.state(), g.expiresAt(), g.source(), g.version());
    }

    private static List<String> fields(Scope s, ResourceRef ref, long version) {
        return new ArrayList<>(List.of(s.tenantId(), s.workspaceId(), ref.type().value(), ref.resourceId(), Long.toString(version)));
    }

    /**
     * 按目标种类展开稳定的身份字段，供请求摘要及目标动作去重使用。
     *
     * @param recipient 待展开的主体、空间角色或整个空间目标。
     * @return 按目标种类固定顺序组织的种类、主体或范围及角色字段。
     */
    private static List<String> recipientFields(GrantRecipient recipient) {
        return switch (recipient) {
            case GrantRecipient.Subject s -> List.of("SUBJECT", s.subjectId());
            case GrantRecipient.SpaceRole s ->
                    List.of("SPACE_ROLE", s.scope().tenantId(), s.scope().workspaceId(), s.role().name());
            case GrantRecipient.Space s -> List.of("WORKSPACE", s.scope().tenantId(), s.scope().workspaceId());
        };
    }

    /**
     * 将一项共享规则展开为目标、规范化动作集合及期限字段，供共享列表排序和请求摘要使用。
     *
     * @param g 待展开的共享规则。
     * @return 按固定顺序组织的规则字段，包含动作数量、排序后的动作及可为空的期限。
     */
    private static List<String> visibilityFields(VisibilityGrant g) {
        List<String> fields = new ArrayList<>(recipientFields(g.recipient()));
        addActions(fields, g.actions());
        fields.add(string(g.expiresAt()));
        return fields;
    }

    /**
     * 按顺序合并两组字段并返回新列表，保留原列表内容。
     *
     * @param a 前一组字段。
     * @param b 后一组字段。
     * @return 先包含 a、再包含 b 的新字段列表。
     */
    private static List<String> concat(List<String> a, List<String> b) {
        List<String> result = new ArrayList<>(a);
        result.addAll(b);
        return result;
    }

    /**
     * 将动作数量及按名称排序的动作追加到字段列表，避免集合遍历顺序影响幂等摘要。
     *
     * @param fields  接收追加内容的可修改字段列表。
     * @param actions 待规范化展开的动作集合。
     */
    private static void addActions(List<String> fields, Set<Action> actions) {
        fields.add(Integer.toString(actions.size()));
        actions.stream().map(Action::value).sorted().forEach(fields::add);
    }

    /**
     * 根据资源类型选择对应的创建动作，供初始登记时校验父空间创建资格；未支持的类型拒绝处理。
     *
     * @param type 待创建资源的类型。
     * @return 与资源类型对应的创建动作。
     */
    private static Action creation(ResourceType type) {
        if (type.equals(MODEL)) {
            return MODEL_CREATE;
        }
        if (type.equals(ARTICLE)) {
            return ARTICLE_CREATE;
        }
        if (type.equals(COLLECTION)) {
            return COLLECTION_CREATE;
        }
        if (type.equals(ASSISTANT)) {
            return ASSISTANT_CREATE;
        }
        throw error(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED);
    }

    /**
     * 根据资源类型选择对应的分享动作，供普通分享及本人分享撤销时校验资格；未支持的类型拒绝处理。
     *
     * @param type 待分享资源的类型。
     * @return 与资源类型对应的分享动作。
     */
    private static Action share(ResourceType type) {
        if (type.equals(MODEL)) {
            return MODEL_SHARE;
        }
        if (type.equals(ARTICLE)) {
            return ARTICLE_SHARE;
        }
        if (type.equals(COLLECTION)) {
            return COLLECTION_SHARE;
        }
        if (type.equals(ASSISTANT)) {
            return ASSISTANT_SHARE;
        }
        throw error(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED);
    }

    /**
     * 按资源类型取得创建者的初始动作预设，供创建事务写入显式 CREATION 来源授权。
     * 预设包含资源 ACL 管理动作，实际访问仍受当前成员资格、公共政策及领域条件约束。
     *
     * @param type 新建资源的类型。
     * @return 对应资源类型的不可变初始动作集合；未支持的类型拒绝处理。
     */
    private static Set<Action> initial(ResourceType type) {
        if (type.equals(MODEL)) {
            return Set.of(MODEL_READ, MODEL_EDIT, MODEL_VALIDATE, MODEL_PUBLISH, MODEL_DISABLE, MODEL_SHARE, MODEL_USE, RESOURCE_ACCESS_MANAGE);
        }
        if (type.equals(ARTICLE)) {
            return Set.of(ARTICLE_READ, ARTICLE_EDIT, ARTICLE_COMMENT, ARTICLE_SHARE, ARTICLE_DELETE, RESOURCE_ACCESS_MANAGE);
        }
        if (type.equals(COLLECTION)) {
            return Set.of(COLLECTION_READ, COLLECTION_EDIT, COLLECTION_SHARE, COLLECTION_DELETE, COLLECTION_AI_USE, RESOURCE_ACCESS_MANAGE);
        }
        if (type.equals(ASSISTANT)) {
            return Set.of(ASSISTANT_READ, ASSISTANT_EDIT, ASSISTANT_PUBLISH, ASSISTANT_DISABLE, ASSISTANT_SHARE, ASSISTANT_USE, RESOURCE_ACCESS_MANAGE);
        }
        throw error(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED);
    }
}
