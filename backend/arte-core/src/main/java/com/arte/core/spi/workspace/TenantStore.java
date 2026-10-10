package com.arte.core.spi.workspace;

import com.arte.core.pojo.workspace.Tenant;

import java.util.Optional;

/**
 * 租户存储，个人主体关联唯一；insert 冲突返回 false，replace 使用版本条件。lock 只在当前事务内持有治理门控。
 * <p>
 * lock 返回取得锁后的最新事实（包括原事务自己的写入），不能使用旧快照、缓存或只读副本。
 * lock 必须取得排他父租户锁，持续到外层事务结束；不得通过另开事务取得。
 * 租户下所有空间、成员和政策治理写入或锁定读取须先持有此父锁；同租户组合操作依赖它串行保护。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface TenantStore {
    /**
     * 按租户 ID 查询租户，不存在时返回空。
     */
    Optional<Tenant> find(String tenantId);

    /**
     * 按主体 ID 查询唯一关联的个人租户，不存在时返回空。
     */
    Optional<Tenant> findPersonal(String subjectId);

    /**
     * 在已持有主体门控的原事务中当前读取个人租户关联，包含原事务自己的写入。
     * 不使用旧快照、缓存或只读副本，不另开事务；不存在时不能回退到旧查询。
     */
    Optional<Tenant> findCurrentPersonal(String subjectId);

    /**
     * 在当前事务内以当前读取锁定并读取租户治理记录。
     */
    Optional<Tenant> lock(String tenantId);

    /**
     * 新增租户，唯一条件冲突时返回 false。
     */
    boolean insert(Tenant tenant);

    /**
     * 按预期版本更新租户，版本条件不满足时返回 false。
     */
    boolean replace(Tenant tenant, long expectedVersion);
}
