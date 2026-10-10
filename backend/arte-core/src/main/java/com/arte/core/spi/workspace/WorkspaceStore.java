package com.arte.core.spi.workspace;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.Workspace;
import com.arte.core.pojo.workspace.WorkspaceCandidatePage;

import java.util.Optional;

/**
 * 按完整范围存取空间，跨租户 ID 不可见；负责人不重复保存。候选查询不承担公共授权算法，服务须最终筛选及计算可见 total。
 * <p>
 * lock 返回取得锁后的最新事实（包括原事务自己的写入），不能使用旧快照、缓存或只读副本。
 * 锁持续到外层事务结束；不得通过另开事务取得。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface WorkspaceStore {

    /**
     * 按完整租户与空间范围查询空间，不存在或范围不匹配时返回空。
     */
    Optional<Workspace> find(Scope scope);

    /**
     * 在当前事务内按完整范围锁定并读取空间治理记录，须先持有对应排他租户父锁。
     */
    Optional<Workspace> lock(Scope scope);

    /**
     * 新增空间，唯一条件冲突时返回 false。
     */
    boolean insert(Workspace workspace);

    /**
     * 按预期版本更新空间，版本条件不满足时返回 false。
     */
    boolean replace(Workspace workspace, long expectedVersion);

    /**
     * 按 updatedAt 降序、id 排序的受信不透明游标；limit 为 1..100，不能暴露候选总数。
     */
    WorkspaceCandidatePage scanMembershipCandidates(String subjectId, String cursor, int limit);
}
