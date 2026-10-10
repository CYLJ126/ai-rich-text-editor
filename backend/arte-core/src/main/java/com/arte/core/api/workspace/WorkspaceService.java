package com.arte.core.api.workspace;

import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import com.arte.core.pojo.workspace.WorkspaceViews;

/**
 * 空间公共 API
 * <p>
 * 读结果按当前主体过滤，total 仅可见范围，创建及改名使用本地事务和回执。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface WorkspaceService {

    /**
     * 分页查询当前操作者可访问的空间，total 只统计可见范围。
     * 按 createdAt 降序、tenantId 和 workspaceId 升序稳定排列；逐项检查当前资格，不提供扫描期间成员变化的目录快照。
     * 每批最多 100 项，授权事实批量读取，摘要复用同一次验证的事实；为精确可见 total 仍扫描全部候选。
     *
     * @param actor 经可信认证适配确认的当前操作者
     * @param query 分页查询条件
     * @return 可访问空间摘要的分页结果
     */
    PageResult<WorkspaceViews.WorkspaceSummary> listAccessible(TrustedActor actor, PageQuery query);

    /**
     * 查询当前操作者可见的空间公开元数据。
     *
     * @param actor 经可信认证适配确认的当前操作者
     * @param scope 当前选择的租户与工作空间范围，仍需服务端验证
     * @return 空间公开元数据、当前角色及版本
     */
    WorkspaceViews.WorkspaceView get(TrustedActor actor, Scope scope);

    /**
     * 在指定租户内创建空间，并登记初始负责人。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 空间创建命令，包含租户、名称、描述及命令元数据
     * @return 空间创建回执，包含新空间信息
     */
    CommandReceipt<WorkspaceViews.WorkspaceView> create(TrustedActor actor, WorkspaceCommands.CreateWorkspace command);

    /**
     * 更新空间名称及描述，不改变归属、负责人或模型默认配置。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 空间改名命令，包含空间、名称、描述、预期版本及命令元数据
     * @return 空间更新回执，包含更新后的空间信息
     */
    CommandReceipt<WorkspaceViews.WorkspaceView> rename(TrustedActor actor, WorkspaceCommands.RenameWorkspace command);
}
