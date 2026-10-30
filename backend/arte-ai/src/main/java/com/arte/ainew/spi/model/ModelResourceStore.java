package com.arte.ainew.spi.model;

import com.arte.ainew.pojo.model.ModelResource;
import com.arte.ainew.pojo.model.ModelResourceVersion;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.PageResult;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.Optional;

/**
 * 模型资源管理持久化接口
 * <p>
 * 原始管理事实，不授予权限。当前方法必须在原写事务父范围门控下读取。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 22:40 ✾
 */
public interface ModelResourceStore {

    Optional<ModelResource> find(ResourceRef resource);

    Optional<ModelResource> findCurrent(ResourceRef resource);

    boolean insert(ModelResource resource);

    boolean replace(ModelResource resource, long expectedVersion);

    Optional<ModelResourceVersion> findBinding(String tenantId, DefinitionRef binding);

    Optional<ModelResourceVersion> findCurrentBinding(String tenantId, DefinitionRef binding);

    PageResult<ModelResourceVersion> versions(ResourceRef resource, PageQuery query);

    /**
     * 受信快照的定义来源登记与版本关联加入同一事务，冲突不得覆盖。
     */
    boolean insertVersion(ModelResourceVersion version);
}
