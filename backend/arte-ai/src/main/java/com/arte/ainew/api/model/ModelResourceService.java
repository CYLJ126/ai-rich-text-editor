package com.arte.ainew.api.model;

import com.arte.ainew.pojo.model.ModelResource;
import com.arte.ainew.pojo.model.ModelResourceCommands;
import com.arte.ainew.pojo.model.ModelResourceVersion;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.*;

import java.util.List;

/**
 * 模型资源管理服务接口
 * <p>
 * 同步模型管理应用服务，由宿主 HTTP 等适配器在数据库线程调用；创建或关联模型不自动切换执行资格来源。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 22:40 ✾
 */
public interface ModelResourceService {

    /**
     * 返回当前主体可执行的模型管理动作，仅作为展示快照。
     */
    List<String> actions(TrustedActor actor, Scope scope, ResourceRef resource);

    /**
     * 查询当前范围可创建的模型归属，仅供展示；提交时仍复核当前资格。
     */
    List<ModelResourceCommands.ModelKind> creationOptions(TrustedActor actor, Scope scope);

    CommandReceipt<ModelResource> create(TrustedActor actor, ModelResourceCommands.Create command);

    CommandReceipt<ModelResourceVersion> associate(TrustedActor actor, ModelResourceCommands.Associate command);

    CommandReceipt<ModelResource> disable(TrustedActor actor, ModelResourceCommands.Disable command);

    ModelResource get(TrustedActor actor, Scope scope, ResourceRef resource);

    PageResult<ModelResourceVersion> versions(TrustedActor actor, Scope scope, ResourceRef resource, PageQuery query);
}
