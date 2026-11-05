package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.TenantTypeEnum;
import com.arte.core.pojo.platform.Scope;

import java.util.List;

/**
 * 同一次治理当前读取得的政策、父记录版本和权限代次，供管理端构造 CAS 命令。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 17:22 ✾
 */
public final class WorkspacePolicyViews {

    private WorkspacePolicyViews() {
    }

    public record TenantPolicyView(TenantPolicy policy, long tenantVersion, long tenantEpoch,
                                   String tenantName, TenantTypeEnum tenantType) {
    }

    public record WorkspacePolicyView(WorkspacePolicy policy, long workspaceVersion, long workspaceEpoch,
                                      long tenantPolicyVersion, long tenantEpoch, String tenantName,
                                      TenantTypeEnum tenantType, PolicyEffect effect) {
    }

    /**
     * 只含空间可核验的交集及本空间超限字段，不返回租户完整名单。
     */
    public record PolicyEffect(PolicyRules effective, List<String> limitedFields) {
    }

    public record PolicyPreview(Scope scope, long workspaceVersion, long workspacePolicyVersion,
                                long tenantPolicyVersion,
                                PolicyEffect proposed) {
    }
}
