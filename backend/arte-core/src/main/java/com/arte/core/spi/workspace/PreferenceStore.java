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
     * 在当前事务内锁定主体的初始化／偏好门控，即使偏好尚不存在也必须串行化。
     * 适配器可锁稳定身份行；不能以查询不存在的偏好行或 JVM 锁代替数据库门控。
     * 默认治理写入与最终授权均先取得操作者的此锁，再取得租户及空间门控。
     */
    void lockSubject(String subjectId);

    /**
     * 按主体 ID 查询本人的空间偏好，不存在时返回空。
     */
    Optional<WorkspacePreference> find(String subjectId);

    /**
     * 在当前事务中读取最新偏好及默认关联，包含原事务自己的写入。
     * 不读取旧快照、缓存或只读副本，不另开事务；不可默认委托普通查询。
     * 本人初始化及更新须先持主体门控；默认关联仅作受保护空间判定，不授予权限。
     */
    Optional<WorkspacePreference> findCurrent(String subjectId);

    /**
     * 新增主体的空间偏好，唯一条件冲突时返回 false。
     */
    boolean insert(WorkspacePreference preference);

    /**
     * 按预期版本更新空间偏好，保留默认空间关联，版本条件不满足时返回 false。
     */
    boolean replace(WorkspacePreference preference, long expectedVersion);
}
