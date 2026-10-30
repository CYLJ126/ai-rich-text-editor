package com.arte.ainew.api.model;

import com.arte.ainew.pojo.model.ModelResource;
import com.arte.ainew.pojo.model.ModelResourceCommands;
import com.arte.ainew.pojo.model.ModelResourceVersion;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.*;

/**
 * 模型资源管理服务接口
 * <p>
 * 同步内部管理应用服务，由宿主在数据库线程调用；本批不开放 HTTP 或切换执行资格来源。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 22:40 ✾
 */
public interface ModelResourceService {

    CommandReceipt<ModelResource> create(TrustedActor actor, ModelResourceCommands.Create command);

    CommandReceipt<ModelResourceVersion> associate(TrustedActor actor, ModelResourceCommands.Associate command);

    CommandReceipt<ModelResource> disable(TrustedActor actor, ModelResourceCommands.Disable command);

    ModelResource get(TrustedActor actor, Scope scope, ResourceRef resource);

    PageResult<ModelResourceVersion> versions(TrustedActor actor, Scope scope, ResourceRef resource, PageQuery query);
}
