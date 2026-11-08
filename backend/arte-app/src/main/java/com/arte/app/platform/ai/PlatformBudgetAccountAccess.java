package com.arte.app.platform.ai;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.BudgetAccountQueryService;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.Reconciliation;
import com.arte.ainew.spi.auth.BudgetReconciliationAccess;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.identity.RbacToolPermissionReader;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.enums.access.GrantStateEnum;
import com.arte.core.enums.access.ResourceOwnershipTypeEnum;
import com.arte.core.enums.access.ResourceVisibilityEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.pojo.access.*;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.VerifiedScope;
import com.arte.core.spi.access.DomainAccessConditionProvider;
import com.arte.core.spi.access.ResourceDescriptorProvider;
import com.arte.core.spi.access.ResourceGrantStore;
import com.arte.core.spi.platform.AuditSink;
import com.arte.core.spi.platform.PublicTransactionRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.arte.core.constant.platform.PlatformActions.BUDGET_ACCOUNT_RECONCILE;
import static com.arte.core.constant.platform.PlatformResourceTypes.BUDGET_ACCOUNT;

/**
 * 账户财务授权适配
 * <p>
 * 公共动作、当前成员和独立核对操作共同约束真实 actor，不模拟目标 owner。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/08 17:30 ✾
 */
public final class PlatformBudgetAccountAccess implements BudgetReconciliationAccess, ResourceDescriptorProvider, DomainAccessConditionProvider {

    private final JdbcTemplate jdbc;
    private final JacksonExecutionRecordCodec codec = new JacksonExecutionRecordCodec();
    private final RbacIdentityProvider identities;
    private final RbacToolPermissionReader permissions;
    private final PublicTransactionRunner transactions;
    private final WorkspaceGovernanceLocks locks;
    private final ObjectProvider<AuthorizationService> authorization;
    private final ResourceGrantStore grants;
    private final AuditSink audit;
    private final Clock clock;
    private final Scheduler scheduler;

    public PlatformBudgetAccountAccess(DataSource source, RbacIdentityProvider identities, RbacToolPermissionReader permissions,
                                       PublicTransactionRunner transactions, WorkspaceGovernanceLocks locks, ObjectProvider<AuthorizationService> authorization,
                                       ResourceGrantStore grants, AuditSink audit, Clock clock, Scheduler scheduler) {
        this.jdbc = new JdbcTemplate(source);
        this.identities = identities;
        this.permissions = permissions;
        this.transactions = transactions;
        this.locks = locks;
        this.authorization = authorization;
        this.grants = grants;
        this.audit = audit;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Budget account access denied");
    }

    private AuthorizationService authorization() {
        return authorization.getObject();
    }

    private Scope scope(ExecutionContext actor) {
        return new Scope(actor.authorization().tenantId(), actor.authorization().workspaceId());
    }

    private BudgetCommands.Account account(ResourceRef ref) {
        return account(ref, false);
    }

    private BudgetCommands.Account account(ResourceRef ref, boolean current) {
        if (!BUDGET_ACCOUNT.equals(ref.type())) throw denied();
        var rows = jdbc.queryForList("SELECT workspace_snapshot FROM arte_ai_account WHERE id_key=?" + (current ? " FOR UPDATE" : ""), String.class, hash(ref.resourceId()));
        if (rows.size() != 1) throw denied();
        BudgetCommands.Account account;
        try {
            account = codec.decode(rows.getFirst(), BudgetCommands.Account.class);
        } catch (RuntimeException corrupt) {
            throw new IllegalStateException("Account ledger identity unavailable", corrupt);
        }
        if (!ref.equals(BudgetAccountQueryService.accountRef(account))) throw denied();
        return account;
    }

    /**
     * 只登记真实账户的空 ACL 根，不授予创建者、空间管理员或普通成员任何动作。须加入预算准备原事务。
     */
    public void registerInCurrentTransaction(BudgetCommands.Account account) {
        transactions.requireWritable();
        var ref = BudgetAccountQueryService.accountRef(account);
        var current = account(ref);
        if (!current.owner().equals(account.owner())) throw denied();
        if (grants.lockAccess(ref).isEmpty() && !grants.insertAccess(new ResourceAccess(ref,
                new ResourceOwnership(ResourceOwnershipTypeEnum.WORKSPACE, account.owner().tenantId(), account.owner().workspaceId()),
                ResourceVisibilityEnum.EXPLICIT, 0)))
            throw new IllegalStateException("Account ACL registration conflict");
    }

    @Override
    public boolean supports(ResourceType type) {
        return BUDGET_ACCOUNT.equals(type);
    }

    @Override
    public boolean supports(ResourceType type, Action action) {
        return supports(type);
    }

    @Override
    public Optional<ResourceDescriptor> find(ResourceRef ref) {
        return descriptor(ref, false);
    }

    private Optional<ResourceDescriptor> descriptor(ResourceRef ref, boolean current) {
        try {
            var account = account(ref, current);
            return Optional.of(new ResourceDescriptor(ref, new ResourceOwnership(ResourceOwnershipTypeEnum.WORKSPACE,
                    account.owner().tenantId(), account.owner().workspaceId()), "ACTIVE", 0, account.owner().subjectId()));
        } catch (AccessDeniedException missing) {
            return Optional.empty();
        }
    }

    /**
     * 同库锁定当前身份；最终结算先锁公共门控，再锁调用，最后进入此账户读取，保持既有业务锁序。
     */
    @Override
    public Optional<ResourceDescriptor> findCurrent(ResourceRef ref) {
        transactions.requireWritable();
        return descriptor(ref, true);
    }

    @Override
    public AccessReasonEnum evaluate(TrustedActor actor, AccessRequest request, ResourceDescriptor descriptor, VerifiedScope scope) {
        return request.scope().workspaceId().equals(descriptor.ownership().workspaceId()) ? AccessReasonEnum.ALLOWED : AccessReasonEnum.DOMAIN_DENIED;
    }

    @Override
    public AccessReasonEnum evaluateCurrent(TrustedActor actor, AccessRequest request, ResourceDescriptor descriptor, VerifiedScope scope) {
        return evaluate(actor, request, descriptor, scope);
    }

    private TrustedActor currentActor(ExecutionContext context) {
        if (!clock.instant().isBefore(context.deadline()) || !context.authorization().scopes().contains(AdmissionAuthorization.READ))
            throw denied();
        var expected = context.authorization().principal();
        var principal = identities.findCurrentExecutionPrincipal(expected).filter(p -> p.status() == PrincipalStatusEnum.ACTIVE).orElseThrow(PlatformBudgetAccountAccess::denied);
        if (!expected.sameIdentityAs(principal)) throw denied();
        return new TrustedActor(principal, clock.instant());
    }

    private String check(ResourceRef ref, BudgetCommands.Account expectedAccount, ExecutionContext context, boolean write) {
        transactions.requireWritable();
        var scope = scope(context);
        if (!scope.tenantId().equals(ref.tenantId()) || !scope.tenantId().equals(expectedAccount.owner().tenantId())
                || !scope.workspaceId().equals(expectedAccount.owner().workspaceId())) throw denied();
        locks.scopes(context.authorization().principal().subjectId(), List.of(scope));
        var actor = currentActor(context);
        var current = account(ref, true);
        if (!current.owner().equals(expectedAccount.owner()) || !current.budgetRef().equals(expectedAccount.budgetRef()))
            throw denied();
        var verified = authorization().requireInCurrentTransaction(actor, new AccessRequest(scope, ref, BUDGET_ACCOUNT_RECONCILE, "budget-reconciliation"));
        if (write && (!context.authorization().scopes().contains(AdmissionAuthorization.BUDGET_ADMIN)
                || !permissions.current(context.authorization().principal().subjectName()).contains(AdmissionAuthorization.BUDGET_ADMIN)))
            throw denied();
        return ref.type().value() + ":" + com.arte.core.application.platform.DefaultCommandExecutor.digest(
                List.of(ref.tenantId(), ref.type().value(), ref.resourceId())) + ":acl=" + verified.versions().aclVersion();
    }

    @Override
    public Mono<Permit> authorize(ResourceRef ref, ExecutionContext context, boolean submitting) {
        return Mono.fromCallable(() -> transactions.required(() -> {
                    if (!scope(context).tenantId().equals(ref.tenantId())) throw denied();
                    var account = account(ref);
                    check(ref, account, context, submitting);
                    return (Permit) new Permit() {
                        private String authority;

                        @Override
                        public BudgetCommands.Account account() {
                            return account;
                        }

                        @Override
                        public ResourceRef accountRef() {
                            return ref;
                        }

                        @Override
                        public boolean allowsConfirmation() {
                            return submitting;
                        }

                        @Override
                        public void lockScopeInCurrentTransaction() {
                            transactions.requireWritable();
                            locks.scopes(context.authorization().principal().subjectId(), List.of(scope(context)));
                        }

                        @Override
                        public String requireInCurrentTransaction() {
                            return authority = check(ref, account, context, submitting);
                        }

                        @Override
                        public void auditInCurrentTransaction(Reconciliation.Receipt receipt) {
                            transactions.requireWritable();
                            if (!submitting || authority == null || !account.owner().equals(receipt.targetOwner())
                                    || !context.authorization().principal().equals(receipt.reviewer())) throw denied();
                            audit.append(new AuditEntry(UUID.randomUUID().toString(), receipt.reviewer().subjectId(),
                                    new CommandScope(account.owner().tenantId(), account.owner().workspaceId()), "budget-account:reconciled",
                                    receipt.invocationId(), "owner=" + account.owner().subjectId() + ";authority=" + authority + ";evidence=" + receipt.evidenceRef() + ";trace=" + receipt.traceId(), clock.instant()));
                        }
                    };
                })).subscribeOn(scheduler).timeout(Duration.between(clock.instant(), context.deadline()))
                .onErrorMap(com.arte.core.exception.platform.PlatformException.class, failure ->
                        failure.getCode() == com.arte.core.enums.platform.PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE ? failure : denied());
    }

    @Override
    public Mono<Handling> handling(BudgetCommands.Account supplied, ExecutionContext context, boolean budgetAdmin) {
        return Mono.fromCallable(() -> transactions.readOnly(() -> {
            var ref = BudgetAccountQueryService.accountRef(supplied);
            var account = account(ref);
            if (!account.owner().equals(ExecutionOwners.from(context))) throw denied();
            var caller = identities.findBySubjectId(context.authorization().principal().subjectId()).filter(p -> p.status() == PrincipalStatusEnum.ACTIVE)
                    .orElseThrow(PlatformBudgetAccountAccess::denied);
            if (budgetAdmin && permissions.snapshot(context.authorization().principal().subjectName()).contains(AdmissionAuthorization.BUDGET_ADMIN)
                    && authorization().check(new TrustedActor(caller, clock.instant()), new AccessRequest(scope(context), ref, BUDGET_ACCOUNT_RECONCILE, "budget-handling")).allowed())
                return new Handling("SELF_SERVICE", null);
            // 单一具名主责任人；角色／空间授权不能被展示为虚构接单人。
            ResourceGrant primary = null;
            for (int page = 1; page <= 100; page++) {
                var values = grants.findGrants(ref, new PageQuery(page, 100));
                if (values.total() > 10000) throw denied();
                for (var grant : values.records()) {
                    if (!grant.action().equals(BUDGET_ACCOUNT_RECONCILE) || grant.state() != GrantStateEnum.ACTIVE
                            || grant.expiresAt() != null && !clock.instant().isBefore(grant.expiresAt())) continue;
                    if (!(grant.target() instanceof GrantTarget.Subject)) continue;
                    if (primary != null) return new Handling("UNASSIGNED", null);
                    primary = grant;
                }
                if (page * 100L >= values.total()) break;
            }
            if (primary != null && primary.target() instanceof GrantTarget.Subject subject) {
                var principal = identities.findBySubjectId(subject.subjectId()).filter(p -> p.status() == PrincipalStatusEnum.ACTIVE);
                var name = identities.subjectName(subject.subjectId());
                if (principal.isPresent() && name.isPresent() && permissions.snapshot(name.get()).contains(AdmissionAuthorization.BUDGET_ADMIN)
                        && authorization().check(new TrustedActor(principal.get(), clock.instant()), new AccessRequest(scope(context), ref, BUDGET_ACCOUNT_RECONCILE, "budget-handling")).allowed())
                    // 登录名可能是邮箱／手机号，仅用于内部 RBAC 检查；没有授权显示目录时只公开稳定标识。
                    return new Handling("ASSIGNED", new Responsible(subject.subjectId(), null));
            }
            return new Handling("UNASSIGNED", null);
        })).subscribeOn(scheduler);
    }

    private static String hash(String value) {
        try {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            digest.update(bytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
