package com.arte.ainew.spi.model;

import com.arte.ainew.pojo.model.ModelSharingPolicy;
import com.arte.core.pojo.access.ResourceRef;

import java.util.Optional;

/**
 * 原始范围和凭据用途事实；当前读要求原事务父租户门控。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/01 19:25 ✾
 */
public interface ModelSharingStore {

    Optional<ModelSharingPolicy> find(ResourceRef resource, boolean current);

    boolean insert(ModelSharingPolicy policy);

    boolean replace(ModelSharingPolicy policy, long expectedVersion);

    /**
     * 有界核验整个租户的权威与索引，缺失索引不能伪装为无可用模型。
     */
    void validateTenant(String tenantId, boolean current);
}
