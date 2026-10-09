package com.arte.core.api.workspace;

import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.TenantCommands;
import com.arte.core.pojo.workspace.TenantPolicy;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import com.arte.core.pojo.workspace.WorkspacePolicy;

/**
 * 获授权治理者的结构化政策 API
 * <p>
 * 空间只收紧租户上限，版本及 epoch 同事务提交。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface WorkspacePolicyService {

    /**
     * 查询当前操作者有资格管理的租户政策。
     *
     * @param actor    经可信认证适配确认的当前操作者
     * @param tenantId 目标租户 ID
     * @return 租户结构化政策及版本
     */
    TenantPolicy getTenantPolicy(TrustedActor actor, String tenantId);

    /**
     * 查询当前操作者有资格管理的空间政策。
     *
     * @param actor 经可信认证适配确认的当前操作者
     * @param scope 当前选择的租户与工作空间范围，仍需服务端验证
     * @return 空间结构化政策及版本
     */
    WorkspacePolicy getWorkspacePolicy(TrustedActor actor, Scope scope);

    /**
     * 更新租户政策，并在同一事务内更新权限版本。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 租户政策更新命令，包含政策内容、预期版本及命令元数据
     * @return 政策更新回执，包含更新后的租户政策
     */
    CommandReceipt<TenantPolicy> updateTenantPolicy(TrustedActor actor, TenantCommands.UpdateTenantPolicy command);

    /**
     * 在租户政策上限内更新空间政策及权限版本。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 空间政策更新命令，包含政策内容、预期版本及命令元数据
     * @return 政策更新回执，包含更新后的空间政策
     */
    CommandReceipt<WorkspacePolicy> updateWorkspacePolicy(TrustedActor actor, WorkspaceCommands.UpdateWorkspacePolicy command);
}
