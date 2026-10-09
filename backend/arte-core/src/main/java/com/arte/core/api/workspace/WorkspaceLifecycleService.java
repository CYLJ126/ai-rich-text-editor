package com.arte.core.api.workspace;

import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.ClosurePreview;
import com.arte.core.pojo.workspace.ClosureView;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import com.arte.core.pojo.workspace.WorkspaceViews;

/**
 * 生命周期公共 API
 * <p>
 * 首版不提供取消关闭或 DELETED 恢复，领域收尾未完成不得标记删除。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface WorkspaceLifecycleService {

    /**
     * 预检空间关闭的领域阻塞项，不执行关闭或清理。
     *
     * @param actor 经可信认证适配确认的当前操作者
     * @param scope 当前选择的租户与工作空间范围，仍需服务端验证
     * @return 空间关闭预检结果及阻塞项
     */
    ClosurePreview preview(TrustedActor actor, Scope scope);

    /**
     * 归档空间，停止新增普通业务写入。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 归档命令，包含空间、预期空间版本、原因及命令元数据
     * @return 归档操作回执，包含更新后的空间信息
     */
    CommandReceipt<WorkspaceViews.WorkspaceView> archive(TrustedActor actor, WorkspaceCommands.ChangeWorkspaceState command);

    /**
     * 取消空间归档，按当前政策恢复可用状态。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 取消归档命令，包含空间、预期空间版本、原因及命令元数据
     * @return 取消归档回执，包含更新后的空间信息
     */
    CommandReceipt<WorkspaceViews.WorkspaceView> unarchive(TrustedActor actor, WorkspaceCommands.ChangeWorkspaceState command);

    /**
     * 受理空间关闭并进入收尾流程，受理不代表资源已清理完成。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 关闭命令，包含空间、预期空间版本、原因及命令元数据
     * @return 关闭受理回执，包含关闭操作信息及状态
     */
    CommandReceipt<ClosureView> close(TrustedActor actor, WorkspaceCommands.CloseWorkspace command);

    /**
     * 查询指定空间关闭操作的当前进度。
     *
     * @param actor       经可信认证适配确认的当前操作者
     * @param scope       当前选择的租户与工作空间范围，仍需服务端验证
     * @param operationId 目标操作 ID
     * @return 关闭操作状态、阻塞信息及版本
     */
    ClosureView progress(TrustedActor actor, Scope scope, String operationId);
}
