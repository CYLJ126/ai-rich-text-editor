package com.arte.core.pojo.workspace;

/**
 * 同一次治理当前读取得的政策、父记录版本和权限代次，供管理端构造 CAS 命令。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 17:22 ✾
 */
public final class WorkspacePolicyViews {

    private WorkspacePolicyViews() {
    }

    public record TenantPolicyView(TenantPolicy policy, long tenantVersion, long tenantEpoch) {
    }

    public record WorkspacePolicyView(WorkspacePolicy policy, long workspaceVersion, long workspaceEpoch,
                                      long tenantPolicyVersion, long tenantEpoch) {
    }
}
