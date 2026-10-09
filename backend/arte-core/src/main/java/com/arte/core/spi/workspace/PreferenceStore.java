package com.arte.core.spi.workspace;

import com.arte.core.pojo.workspace.WorkspacePreference;

import java.util.Optional;

/**
 * 本人默认关联唯一且不可通过 replace 修改；首建与个人初始化同事务，更新版本 CAS。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface PreferenceStore {
    /**
     * 按主体 ID 查询本人的空间偏好，不存在时返回空。
     */
    Optional<WorkspacePreference> find(String subjectId);

    /**
     * 新增主体的空间偏好，唯一条件冲突时返回 false。
     */
    boolean insert(WorkspacePreference preference);

    /**
     * 按预期版本更新空间偏好，保留默认空间关联，版本条件不满足时返回 false。
     */
    boolean replace(WorkspacePreference preference, long expectedVersion);
}
