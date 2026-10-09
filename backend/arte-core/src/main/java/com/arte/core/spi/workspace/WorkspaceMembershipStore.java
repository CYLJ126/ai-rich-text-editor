package com.arte.core.spi.workspace;

import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.PageResult;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.WorkspaceMembership;

import java.util.Optional;

/**
 * 工作空间成员关系存储，负责完整租户与空间范围内的成员记录。
 * <p>
 * 成员关联按空间与主体唯一；撤销保留记录，重新加入时成员代次递增。
 * OWNER 交接及唯一性由公共应用层在治理门控事务内保证，空间角色不隐式赋予私有资源读取权限。
 * 租户成员记录由 {@link TenantMembershipStore} 管理；涉及两类成员的操作仍须参与同一事务。
 * <p>
 * lock 返回取得锁后的最新事实（包括原事务自己的写入），不能使用旧快照、缓存或只读副本。
 * 锁持续到外层事务结束；不得通过另开事务取得。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface WorkspaceMembershipStore {

    /**
     * 分页查询指定空间的成员关联，访问资格由公共服务判定。
     *
     * @param scope 目标租户与工作空间范围
     * @param query 分页查询条件
     * @return 空间成员记录的分页结果
     */
    PageResult<WorkspaceMembership> list(Scope scope, PageQuery query);

    /**
     * 按完整空间范围和主体 ID 查询空间成员关联。
     *
     * @param scope     目标租户与工作空间范围
     * @param subjectId 稳定主体 ID
     * @return 空间成员关联，不存在或范围不匹配时返回空
     */
    Optional<WorkspaceMembership> find(Scope scope, String subjectId);

    /**
     * 在指定空间内按成员 ID 查询成员关联。
     *
     * @param scope    目标租户与工作空间范围
     * @param memberId 空间成员记录 ID
     * @return 空间成员关联，不存在或范围不匹配时返回空
     */
    Optional<WorkspaceMembership> findById(Scope scope, String memberId);

    /**
     * 在当前事务内按完整范围锁定并读取指定主体的空间成员关联。
     *
     * @param scope     目标租户与工作空间范围
     * @param subjectId 稳定主体 ID
     * @return 已锁定的空间成员关联，不存在或范围不匹配时返回空
     */
    Optional<WorkspaceMembership> lock(Scope scope, String subjectId);

    /**
     * 新增空间成员关联，不覆盖已有关联。
     *
     * @param member 待保存的空间成员记录
     * @return 写入成功时返回 true，唯一条件冲突时返回 false
     */
    boolean insert(WorkspaceMembership member);

    /**
     * 按预期版本更新空间成员关联。
     *
     * @param member          更新后的空间成员记录
     * @param expectedVersion 更新前的预期记录版本
     * @return 更新成功时返回 true，版本条件不满足时返回 false
     */
    boolean replace(WorkspaceMembership member, long expectedVersion);
}
