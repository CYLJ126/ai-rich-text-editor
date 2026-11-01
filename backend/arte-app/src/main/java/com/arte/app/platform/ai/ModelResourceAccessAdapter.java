package com.arte.app.platform.ai;

import com.arte.ainew.pojo.model.ModelResource;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.pojo.access.*;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.VerifiedScope;
import com.arte.core.spi.access.DomainAccessConditionProvider;
import com.arte.core.spi.access.ResourceDescriptorProvider;

import java.util.Optional;

import static com.arte.core.constant.platform.PlatformActions.MODEL_USE;
import static com.arte.core.constant.platform.PlatformResourceTypes.MODEL;

/**
 * 模型资源访问适配器
 * <p>
 * 只读模型原始事实，不反调受保护管理 API；不接管运行时 Entitlement。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 22:40 ✾
 */
public final class ModelResourceAccessAdapter implements ResourceDescriptorProvider, DomainAccessConditionProvider {

    private final ModelResourceStore store;
    private com.arte.ainew.spi.model.ModelSharingStore sharing;

    public void sharing(com.arte.ainew.spi.model.ModelSharingStore sharing) {
        this.sharing = sharing;
    }

    public ModelResourceAccessAdapter(ModelResourceStore store) {
        this.store = store;
    }

    @Override
    public boolean supports(ResourceType type) {
        return MODEL.equals(type);
    }

    @Override
    public boolean supports(ResourceType type, Action action) {
        return MODEL.equals(type);
    }

    @Override
    public Optional<ResourceDescriptor> find(ResourceRef ref) {
        return store.find(ref).map(ModelResource::descriptor);
    }

    @Override
    public Optional<ResourceDescriptor> findCurrent(ResourceRef ref) {
        return store.findCurrent(ref).map(ModelResource::descriptor);
    }

    @Override
    public AccessReasonEnum evaluate(TrustedActor actor, AccessRequest request, ResourceDescriptor descriptor, VerifiedScope scope) {
        if (MODEL_USE.equals(request.action()) && !descriptor.domainState().equals(ModelResource.State.PUBLISHED.name()))
            return AccessReasonEnum.DOMAIN_DENIED;
        return range(request, descriptor, false);
    }

    @Override
    public AccessReasonEnum evaluateCurrent(TrustedActor actor, AccessRequest request, ResourceDescriptor descriptor, VerifiedScope scope) {
        if (MODEL_USE.equals(request.action()) && !descriptor.domainState().equals(ModelResource.State.PUBLISHED.name())) {
            return AccessReasonEnum.DOMAIN_DENIED;
        }
        return range(request, descriptor, true);
    }

    private AccessReasonEnum range(AccessRequest request, ResourceDescriptor descriptor, boolean current) {
        if (!MODEL_USE.equals(request.action()) || descriptor.ownership().workspaceId() != null) {
            return AccessReasonEnum.ALLOWED;
        }
        if (sharing == null) {
            return AccessReasonEnum.AUTHORITY_UNAVAILABLE;
        }
        com.arte.ainew.pojo.model.ModelSharingPolicy policy = sharing.find(request.resource(), current).orElse(null);
        if (policy == null) {
            return AccessReasonEnum.AUTHORITY_UNAVAILABLE;
        }
        return policy.workspaceIds().contains(request.scope().workspaceId()) ? AccessReasonEnum.ALLOWED : AccessReasonEnum.DOMAIN_DENIED;
    }
}
