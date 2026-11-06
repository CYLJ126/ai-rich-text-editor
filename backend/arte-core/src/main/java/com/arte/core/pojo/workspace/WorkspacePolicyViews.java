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

    /**
     * 展示租户政策及管理端构造更新命令所需的版本信息。
     *
     * @param policy        当前租户政策
     * @param tenantVersion 同次读取取得的租户记录版本
     * @param tenantEpoch   租户授权代次
     * @param tenantName    租户展示名称
     * @param tenantType    租户类型
     */
    public record TenantPolicyView(TenantPolicy policy, long tenantVersion, long tenantEpoch,
                                   String tenantName, TenantTypeEnum tenantType) {
    }

    /**
     * 展示空间配置的政策、有效规则及相关版本信息。
     *
     * @param policy 当前空间配置的政策
     * @param workspaceVersion 同次读取取得的空间记录版本
     * @param workspaceEpoch 空间授权代次
     * @param tenantPolicyVersion 同次读取取得的租户政策版本
     * @param tenantEpoch 租户授权代次
     * @param tenantName 租户展示名称
     * @param tenantType 租户类型
     * @param effect 空间政策在租户上限下的实际效果
     */
    public record WorkspacePolicyView(WorkspacePolicy policy, long workspaceVersion, long workspaceEpoch,
                                      long tenantPolicyVersion, long tenantEpoch, String tenantName,
                                      TenantTypeEnum tenantType, PolicyEffect effect) {
    }

    /**
     * 只含空间可核验的交集及本空间超限字段，不返回租户完整名单。
     *
     * @param effective 空间政策受租户上限约束后实际生效的规则
     * @param limitedFields 因租户上限而受到限制的空间政策字段名列表
     */
    public record PolicyEffect(PolicyRules effective, List<String> limitedFields) {
    }

    /**
     * 预览拟提交的空间政策在当前租户上限下的效果，并返回本次预览依据的记录版本。
     *
     * @param scope 目标空间范围，包含租户 ID 和空间 ID
     * @param workspaceVersion 同次读取取得的空间记录版本
     * @param workspacePolicyVersion 预览所依据的当前空间政策版本
     * @param tenantPolicyVersion 同次读取取得的租户政策版本
     * @param proposed 拟提交规则在当前租户上限下的预期效果
     */
    public record PolicyPreview(Scope scope, long workspaceVersion, long workspacePolicyVersion,
                                long tenantPolicyVersion,
                                PolicyEffect proposed) {
    }
}
