package com.arte.core.application.access;

import com.arte.core.application.workspace.WorkspaceAccessSupport;
import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.TenantTypeEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.enums.workspace.WorkspaceStateEnum;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.access.ResourceType;
import com.arte.core.pojo.workspace.PolicyRules;

import java.util.Set;

import static com.arte.core.constant.platform.PlatformActions.*;
import static com.arte.core.constant.platform.PlatformResourceTypes.*;

/**
 * 动作授权映射：Action -> AccessReasonEnum
 * rolePresetVersion=1 的基础动作预设；资源内容访问从不由治理角色授予。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
public final class DefaultRolePermissions {

    private DefaultRolePermissions() {
    }

    public static boolean mayCreateWorkspace(WorkspaceAccessSupport.TenantSnapshot tenant) {
        return switch (tenant.member().role()) {
            case OWNER, ADMIN -> true;
            case MEMBER -> tenant.policy().membersMayCreateWorkspace();
            case GUEST -> false;
        };
    }

    public static AccessReasonEnum tenant(Action action, WorkspaceAccessSupport.TenantSnapshot tenant) {
        if (action.equals(TENANT_READ)) return AccessReasonEnum.ALLOWED;
        if (action.equals(TENANT_WORKSPACE_CREATE)) return decision(mayCreateWorkspace(tenant));
        boolean governor = tenant.member().role() == TenantRoleEnum.OWNER || tenant.member().role() == TenantRoleEnum.ADMIN;
        if (Set.of(TENANT_POLICY_WRITE, TENANT_MEMBER_MANAGE).contains(action)) return decision(governor);
        if (action.equals(TENANT_OWNER_TRANSFER)) return decision(tenant.member().role() == TenantRoleEnum.OWNER
                && tenant.tenant().type() == TenantTypeEnum.ORGANIZATION);
        // 租户模型管理及 ACL 管理必须有专门授权，OWNER 预设也不包含它们。
        return AccessReasonEnum.ACTION_NOT_GRANTED;
    }

    public static AccessReasonEnum workspace(Action action, WorkspaceAccessSupport.WorkspaceSnapshot scope) {
        WorkspaceRoleEnum role = scope.member().role();
        if (Set.of(WORKSPACE_READ, RESOURCE_DISCOVER).contains(action)) return AccessReasonEnum.ALLOWED;
        if (scope.workspace().state() == WorkspaceStateEnum.ARCHIVED) {
            return action.equals(WORKSPACE_RESTORE) ? decision(role == WorkspaceRoleEnum.OWNER)
                    : AccessReasonEnum.LIFECYCLE_DENIED;
        }
        if (Set.of(WORKSPACE_UPDATE, WORKSPACE_INVITE, WORKSPACE_MEMBER_MANAGE).contains(action)) {
            return decision(role == WorkspaceRoleEnum.OWNER || role == WorkspaceRoleEnum.ADMIN);
        }
        if (Set.of(WORKSPACE_ADMIN_ASSIGN, WORKSPACE_OWNER_TRANSFER, WORKSPACE_POLICY_WRITE, WORKSPACE_ARCHIVE,
                WORKSPACE_CLOSE, WORKSPACE_RESTORE, WORKSPACE_RESOURCE_GOVERN).contains(action)) {
            return decision(role == WorkspaceRoleEnum.OWNER);
        }
        if (action.equals(WORKSPACE_AI_USE)) {
            // guestAiAllowed 只是政策上限，访客仍需后续显式授权，不能仅靠开关获得调用权。
            if (role == WorkspaceRoleEnum.GUEST && (!scope.policy().rules().guestAiAllowed()
                    || !scope.tenant().policy().rules().guestAiAllowed())) return AccessReasonEnum.POLICY_DENIED;
            return decision(role != WorkspaceRoleEnum.GUEST);
        }
        ResourceType creationType = creationType(action);
        if (creationType != null) {
            PolicyRules tenant = scope.tenant().policy().rules();
            PolicyRules workspace = scope.policy().rules();
            if (!tenant.allowedCreationTypes().contains(creationType)
                    || !workspace.allowedCreationTypes().contains(creationType)) return AccessReasonEnum.POLICY_DENIED;
            boolean standard = role == WorkspaceRoleEnum.OWNER || role == WorkspaceRoleEnum.ADMIN
                    || (role == WorkspaceRoleEnum.MEMBER && Set.of(ARTICLE, COLLECTION).contains(creationType));
            boolean extra = tenant.extraCreationRoles().getOrDefault(creationType, Set.of()).contains(role)
                    && workspace.extraCreationRoles().getOrDefault(creationType, Set.of()).contains(role);
            return decision(standard || extra);
        }
        return AccessReasonEnum.ACTION_NOT_GRANTED;
    }

    private static ResourceType creationType(Action action) {
        if (action.equals(ARTICLE_CREATE)) return ARTICLE;
        if (action.equals(COLLECTION_CREATE)) return COLLECTION;
        if (action.equals(MODEL_CREATE)) return MODEL;
        if (action.equals(ASSISTANT_CREATE)) return ASSISTANT;
        return null;
    }

    private static AccessReasonEnum decision(boolean allowed) {
        return allowed ? AccessReasonEnum.ALLOWED : AccessReasonEnum.ACTION_NOT_GRANTED;
    }
}
