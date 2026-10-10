package com.arte.core.application.workspace;

import com.arte.core.api.platform.CommandReceiptQueryService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.enums.platform.CommandDeliveryEnum;
import com.arte.core.enums.platform.CommandStatusEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.WorkspaceViews;
import com.arte.core.spi.platform.CommandReceiptStore;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.PreferenceStore;
import com.arte.core.spi.workspace.TenantStore;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_READ;

/**
 * 首批成功命令的只读回执查询。仅原主体，按已保存目标取得门控并重检当前读取资格。
 * 不执行原命令、不按创建权限读取、不返回幂等身份／请求摘要／存储信封。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 18:42 ✾
 */
public final class DefaultCommandReceiptQueryService implements CommandReceiptQueryService {

    private final CurrentPrincipalResolver principals;
    private final CommandReceiptStore receipts;
    private final PreferenceStore preferences;
    private final TenantStore tenants;
    private final WorkspaceAccessSupport access;
    private final DefaultAuthorizationService authorization;
    private final WorkspaceGovernanceLocks locks;
    private final PublicTransactionRunner transactions;

    DefaultCommandReceiptQueryService(CurrentPrincipalResolver principals, CommandReceiptStore receipts,
                                      PreferenceStore preferences, TenantStore tenants, WorkspaceAccessSupport access,
                                      DefaultAuthorizationService authorization, WorkspaceGovernanceLocks locks,
                                      PublicTransactionRunner transactions) {
        this.principals = principals;
        this.receipts = receipts;
        this.preferences = preferences;
        this.tenants = tenants;
        this.access = access;
        this.authorization = authorization;
        this.locks = locks;
        this.transactions = transactions;
    }

    @Override
    public CommandReceipt<?> get(TrustedActor actor, String operationId) {
        PlatformChecks.id(operationId, "operationId");
        String subjectId = principals.requireUser(actor).subjectId();
        var located = authorityRead(() -> receipts.findByOperationId(operationId)).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        if (!located.identity().actorSubjectId().equals(subjectId)) throw error(PlatformErrorCodeEnum.NOT_FOUND);
        return transactions.required(() -> {
            locks.subject(subjectId);
            principals.requireUserCurrent(actor);
            var stored = authorityRead(() -> receipts.findCurrent(located.identity())).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
            if (!stored.identity().equals(located.identity()) || !stored.receipt().operationId().equals(operationId))
                throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            Set<Scope> targets = validateAndTargets(stored);
            Object value = stored.receipt().value();
            if (value instanceof WorkspaceViews.PersonalWorkspaceView || value instanceof WorkspaceViews.PreferenceView) {
                var current = authorityRead(() -> preferences.findCurrent(subjectId)).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
                var saved = value instanceof WorkspaceViews.PersonalWorkspaceView personal ? personal.preference()
                        : (WorkspaceViews.PreferenceView) value;
                if (!current.subjectId().equals(subjectId) || !current.defaultScope().equals(saved.defaultScope()))
                    throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            }
            locks.scopes(subjectId, targets);
            for (Scope target : targets) requireVisible(actor, target);
            if (value instanceof WorkspaceViews.PersonalWorkspaceView personal) {
                var tenant = authorityRead(() -> tenants.findCurrentPersonal(subjectId)).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
                var snapshot = access.snapshotCurrent(actor, personal.workspace().scope());
                if (!tenant.id().equals(personal.tenant().id()) || !subjectId.equals(tenant.personalSubjectId())
                        || snapshot.tenant().member().role() != TenantRoleEnum.OWNER || snapshot.member().role() != WorkspaceRoleEnum.OWNER)
                    throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            }
            // 历史回执保持原版本和时间，返回方式标识为 REPLAYED，不改写数据库。
            var receipt = stored.receipt();
            return new CommandReceipt<>(receipt.operationId(), receipt.status(), CommandDeliveryEnum.REPLAYED,
                    receipt.value(), receipt.resultVersion(), receipt.acceptedAt(), receipt.completedAt());
        });
    }

    private Set<Scope> validateAndTargets(StoredCommandReceipt stored) {
        var receipt = stored.receipt();
        if (receipt.status() != CommandStatusEnum.APPLIED) throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        var identityScope = stored.identity().scope();
        return switch (stored.identity().commandType()) {
            case "personal:initialize" -> {
                if (!identityScope.equals(new CommandScope(null, null)) || !stored.resultSchema().equals("personal:view:v1")
                        || !(receipt.value() instanceof WorkspaceViews.PersonalWorkspaceView view)
                        || receipt.resultVersion() != view.preference().version())
                    throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                yield references(view.preference());
            }
            case "preference:update" -> {
                if (!identityScope.equals(new CommandScope(null, null)) || !stored.resultSchema().equals("preference:view:v1")
                        || !(receipt.value() instanceof WorkspaceViews.PreferenceView view)
                        || receipt.resultVersion() != view.version())
                    throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                yield references(view);
            }
            case "workspace:create", "workspace:rename" -> {
                if (!stored.resultSchema().equals("workspace:view:v1") || !(receipt.value() instanceof WorkspaceViews.WorkspaceView view)
                        || receipt.resultVersion() != view.version() || !view.scope().tenantId().equals(identityScope.tenantId())
                        || (stored.identity().commandType().equals("workspace:create") ? identityScope.workspaceId() != null
                        : !view.scope().workspaceId().equals(identityScope.workspaceId())))
                    throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                yield Set.of(view.scope());
            }
            default -> throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        };
    }

    private static Set<Scope> references(WorkspaceViews.PreferenceView view) {
        if (view.favorites().size() > 100) throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        Set<Scope> scopes = new HashSet<>(view.favorites());
        scopes.add(view.defaultScope());
        if (view.startupScope() != null) scopes.add(view.startupScope());
        if (view.lastScope() != null) scopes.add(view.lastScope());
        return scopes;
    }

    private void requireVisible(TrustedActor actor, Scope scope) {
        try {
            authorization.requireInCurrentTransaction(actor,
                    DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_READ, "command-receipt"));
        } catch (PlatformException failure) {
            switch (failure.getCode()) {
                case NOT_FOUND, ACCESS_DENIED, POLICY_DENIED, TENANT_INACTIVE, SPACE_READ_ONLY, SPACE_CLOSING ->
                        throw error(PlatformErrorCodeEnum.NOT_FOUND);
                default -> throw failure;
            }
        }
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "command-receipt");
    }

    private static <T> T authorityRead(Supplier<T> read) {
        try {
            return read.get();
        } catch (PlatformException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            var unavailable = error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            unavailable.initCause(failure);
            throw unavailable;
        }
    }
}
