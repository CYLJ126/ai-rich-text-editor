package com.arte.core.spi.workspace;

import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.PageResult;
import com.arte.core.pojo.workspace.TenantMembership;

import java.util.Optional;

/**
 * 租户成员关系存储，负责租户范围内的成员记录。
 * <p>
 * 成员关联按租户与主体唯一；撤销保留记录，重新加入时成员代次递增。
 * OWNER 交接及唯一性由公共应用层在治理门控事务内保证。
 * 空间成员记录由 {@link WorkspaceMembershipStore} 管理；涉及两类成员的操作仍须参与同一事务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface TenantMembershipStore {

    /**
     * 分页查询指定租户的成员关联，访问资格由公共服务判定。
     *
     * @param tenantId 目标租户 ID
     * @param query    分页查询条件
     * @return 租户成员记录的分页结果
     */
    PageResult<TenantMembership> list(String tenantId, PageQuery query);

    /**
     * 按租户和主体 ID 查询租户成员关联，不存在时返回空。
     *
     * @param tenantId  目标租户 ID
     * @param subjectId 稳定主体 ID
     * @return 租户成员关联，不存在时返回空
     */
    Optional<TenantMembership> find(String tenantId, String subjectId);

    /**
     * 在指定租户内按成员 ID 查询成员关联。
     *
     * @param tenantId 目标租户 ID
     * @param memberId 租户成员记录 ID
     * @return 租户成员关联，不存在或租户不匹配时返回空
     */
    Optional<TenantMembership> findById(String tenantId, String memberId);

    /**
     * 在当前事务内锁定并读取指定主体的租户成员关联。
     *
     * @param tenantId  目标租户 ID
     * @param subjectId 稳定主体 ID
     * @return 已锁定的租户成员关联，不存在时返回空
     */
    Optional<TenantMembership> lock(String tenantId, String subjectId);

    /**
     * 新增租户成员关联，不覆盖已有关联。
     *
     * @param member 待保存的租户成员记录
     * @return 写入成功时返回 true，唯一条件冲突时返回 false
     */
    boolean insert(TenantMembership member);

    /**
     * 按预期版本更新租户成员关联。
     *
     * @param member          更新后的租户成员记录
     * @param expectedVersion 更新前的预期记录版本
     * @return 更新成功时返回 true，版本条件不满足时返回 false
     */
    boolean replace(TenantMembership member, long expectedVersion);
}
