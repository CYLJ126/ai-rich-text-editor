package com.arte.core.application.workspace;

import com.arte.core.api.workspace.TenantQueryService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.access.DefaultRolePermissions;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.*;
import com.arte.core.spi.workspace.TenantMembershipStore;
import com.arte.core.utils.platform.PlatformChecks;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static com.arte.core.constant.platform.PlatformActions.*;

/**
 * 租户查询服务类
 * <p>
 * 从当前主体的租户成员关联扫描候选，再使用共用授权事实生成受权投影。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 13:11 ✾
 */
final class DefaultTenantQueryService implements TenantQueryService {

    private final CurrentPrincipalResolver principals;
    private final WorkspaceAccessSupport access;
    private final TenantMembershipStore members;
    private final WorkspaceBrowseBudget budget;
    private final LongSupplier nanoTime;
    private final DefaultAuthorizationService authorization;
    private final DefaultTenantGovernanceService governanceService;

    DefaultTenantQueryService(CurrentPrincipalResolver principals, WorkspaceAccessSupport access,
                              TenantMembershipStore members, WorkspaceBrowseBudget budget, LongSupplier nanoTime,
                              DefaultAuthorizationService authorization, DefaultTenantGovernanceService governanceService) {
        this.principals = principals;
        this.access = access;
        this.members = members;
        this.budget = budget;
        this.nanoTime = nanoTime;
        this.authorization = authorization;
        this.governanceService = governanceService;
    }

    @Override
    public TenantDirectoryViews.BrowseResult browseAccessible(TrustedActor actor, TenantBrowseQuery query, String afterTenantId) {
        if (afterTenantId != null) {
            PlatformChecks.id(afterTenantId, "afterTenantId");
        }
        String subject = principals.requireUser(actor).subjectId();
        long started = nanoTime.getAsLong();
        List<TenantDirectoryViews.Summary> records = new ArrayList<>();
        String boundary = afterTenantId;
        int processed = 0;
        boolean exhausted = false;
        for (int batchNumber = 0; batchNumber < budget.maxBatches() && processed < budget.maxCandidates(); batchNumber++) {
            if (batchNumber > 0 && nanoTime.getAsLong() - started >= budget.maxWorkTime().toNanos()) {
                break;
            }
            int desired = batchNumber == 0 ? Math.min(query.size() * 2, budget.batchSize()) : budget.batchSize();
            int limit = Math.min(desired, budget.maxCandidates() - processed);
            String scanAfter = boundary;
            TenantCandidatePage page = authority(() -> members.scanMembershipCandidates(subject, scanAfter, limit));
            if (page.records().size() > limit) {
                throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            }
            if (page.records().isEmpty()) {
                exhausted = true;
                break;
            }
            // Match the persistence key's UTF-8 binary ordering, including supplementary characters.
            byte[] previous = scanAfter == null ? null : scanAfter.getBytes(StandardCharsets.UTF_8);
            for (String id : page.records()) {
                byte[] next = id.getBytes(StandardCharsets.UTF_8);
                if (previous != null && Arrays.compareUnsigned(next, previous) <= 0) {
                    throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                }
                previous = next;
            }
            WorkspaceAccessSupport.Batch facts = authority(() -> access.readBatch(actor, new HashSet<>(page.records()), Set.of(), false));
            int consumed = 0;
            for (String id : page.records()) {
                boundary = id;
                consumed++;
                processed++;
                WorkspaceAccessSupport.TenantSnapshot snapshot = visibleTenant(() -> facts.directoryTenant(id));
                if (snapshot != null && (query.type() == null || query.type() == snapshot.tenant().type())
                        && snapshot.tenant().name().toLowerCase(Locale.ROOT).contains(query.search())) {
                    records.add(summary(snapshot));
                    if (records.size() == query.size()) {
                        break;
                    }
                }
            }
            exhausted = page.exhausted() && consumed == page.records().size();
            if (exhausted || records.size() == query.size()) {
                break;
            }
        }
        principals.requireUser(actor);
        return new TenantDirectoryViews.BrowseResult(records, exhausted ? null : boundary, exhausted);
    }

    @Override
    public TenantDirectoryViews.Detail getTenant(TrustedActor actor, String tenantId) {
        PlatformChecks.id(tenantId, "tenantId");
        principals.requireUser(actor);
        WorkspaceAccessSupport.TenantSnapshot snapshot = visibleTenant(() -> access.directoryTenant(actor, tenantId));
        if (snapshot == null) {
            throw error(PlatformErrorCodeEnum.NOT_FOUND);
        }
        TenantDirectoryViews.Summary summary = summary(snapshot);
        List<String> actions = new ArrayList<>();
        actions.add(TENANT_READ.value());
        boolean governance = DefaultRolePermissions.tenant(TENANT_POLICY_WRITE, snapshot) == AccessReasonEnum.ALLOWED;
        if (governance) {
            actions.add(TENANT_POLICY_WRITE.value());
        }
        if (governance && authorization.check(actor, DefaultAuthorizationService.tenantRequest(tenantId, TENANT_UPDATE,
                "tenant-directory")).allowed()) {
            actions.add(TENANT_UPDATE.value());
        }
        if (DefaultRolePermissions.mayCreateWorkspace(snapshot)) {
            actions.add(TENANT_WORKSPACE_CREATE.value());
        }
        principals.requireUser(actor);
        return new TenantDirectoryViews.Detail(summary.id(), summary.name(), summary.type(), summary.state(), summary.currentRole(),
                summary.version(), summary.isPersonal(), governance ? TenantDirectoryViews.ViewLevel.GOVERNANCE
                : TenantDirectoryViews.ViewLevel.BASIC, actions);
    }

    @Override
    public TenantDirectoryViews.CreationOptions getCreationOptions(TrustedActor actor) {
        return governanceService.creationOptions(actor);
    }

    private static WorkspaceAccessSupport.TenantSnapshot visibleTenant(Supplier<WorkspaceAccessSupport.TenantSnapshot> read) {
        try {
            WorkspaceAccessSupport.TenantSnapshot snapshot = authority(read);
            return DefaultRolePermissions.tenant(TENANT_READ, snapshot) == AccessReasonEnum.ALLOWED ? snapshot : null;
        } catch (PlatformException failure) {
            if (failure.getCode() == PlatformErrorCodeEnum.NOT_FOUND || failure.getCode() == PlatformErrorCodeEnum.TENANT_INACTIVE) {
                return null;
            }
            throw failure;
        }
    }

    private static TenantDirectoryViews.Summary summary(WorkspaceAccessSupport.TenantSnapshot snapshot) {
        Tenant tenant = snapshot.tenant();
        return new TenantDirectoryViews.Summary(tenant.id(), tenant.name(), tenant.type(), tenant.state(), snapshot.member().role(),
                tenant.version(), snapshot.principal().subjectId().equals(tenant.personalSubjectId()));
    }

    private static <T> T authority(Supplier<T> read) {
        try {
            return read.get();
        } catch (PlatformException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            PlatformException unavailable = error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            unavailable.initCause(failure);
            throw unavailable;
        }
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "tenant-directory");
    }
}
