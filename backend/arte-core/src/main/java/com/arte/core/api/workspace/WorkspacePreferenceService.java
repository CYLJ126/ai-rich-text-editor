package com.arte.core.api.workspace;

import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import com.arte.core.pojo.workspace.WorkspaceViews;

/**
 * 本人偏好 API
 * <p>
 * 默认关联不可通过偏好更新改写，不把偏好作为执行 scope 或授权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface WorkspacePreferenceService {

    /**
     * 查询当前操作者本人的空间偏好。
     *
     * @param actor 经可信认证适配确认的当前操作者
     * @return 本人的默认空间关联及空间偏好
     */
    WorkspaceViews.PreferenceView get(TrustedActor actor);

    /**
     * 更新本人的空间偏好，不改变默认空间关联或授权。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 偏好更新命令，包含偏好内容、预期偏好版本及命令元数据
     * @return 偏好更新回执，包含更新后的本人偏好
     */
    CommandReceipt<WorkspaceViews.PreferenceView> update(TrustedActor actor, WorkspaceCommands.UpdatePreference command);
}
