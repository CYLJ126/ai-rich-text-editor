package com.arte.core.spi.workspace;

import com.arte.core.pojo.workspace.Tenant;

import java.util.Optional;

/**
 * 租户存储，个人主体关联唯一；insert 冲突返回 false，replace 使用版本条件。lock 只在当前事务内持有治理门控。
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
     * 在当前事务内锁定并读取租户治理记录。
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
