package com.arte.core.spi.workspace;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.TenantPolicy;
import com.arte.core.pojo.workspace.WorkspacePolicy;

import java.util.Optional;

/**
 * 同库结构化政策存储，版本 CAS；与租户／空间 epoch 同事务更新，无任意脚本许可。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface PolicyStore {
    /**
     * 查询指定租户的结构化政策，不存在时返回空。
     */
    Optional<TenantPolicy> findTenantPolicy(String tenantId);

    /**
     * 查询指定空间的结构化政策，不存在时返回空。
     */
    Optional<WorkspacePolicy> findWorkspacePolicy(Scope scope);

    /**
     * 在已持有租户门控的原事务中当前读取政策，包含已提交变更及原事务自己的写入。
     * 不使用旧快照、缓存或只读副本，不另开事务；不可默认委托普通查询。
     */
    Optional<TenantPolicy> findCurrentTenantPolicy(String tenantId);

    /**
     * 在已持有租户／空间门控的原事务中当前读取空间政策，读取约束同租户政策。
     */
    Optional<WorkspacePolicy> findCurrentWorkspacePolicy(Scope scope);

    /**
     * 新增租户政策，唯一条件冲突时返回 false。
     */
    boolean insertTenantPolicy(TenantPolicy policy);

    /**
     * 新增空间政策，唯一条件冲突时返回 false。
     */
    boolean insertWorkspacePolicy(WorkspacePolicy policy);

    /**
     * 按预期版本更新租户政策，版本条件不满足时返回 false。
     */
    boolean replaceTenantPolicy(TenantPolicy policy, long expectedVersion);

    /**
     * 按预期版本更新空间政策，版本条件不满足时返回 false。
     */
    boolean replaceWorkspacePolicy(WorkspacePolicy policy, long expectedVersion);
}
