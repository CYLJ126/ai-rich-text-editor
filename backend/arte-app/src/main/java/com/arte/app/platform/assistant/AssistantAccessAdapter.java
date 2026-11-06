package com.arte.app.platform.assistant;

import com.arte.ainew.pojo.assistant.Assistants.Resource;
import com.arte.ainew.pojo.assistant.Assistants.State;
import com.arte.ainew.spi.assistant.AssistantStore;
import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.pojo.access.*;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.VerifiedScope;
import com.arte.core.spi.access.DomainAccessConditionProvider;
import com.arte.core.spi.access.ResourceDescriptorProvider;

import java.util.Optional;

import static com.arte.core.constant.platform.PlatformActions.*;
import static com.arte.core.constant.platform.PlatformResourceTypes.ASSISTANT;

/**
 * Adapts raw workspace-owned assistant facts to the common ACL service.
 */
public final class AssistantAccessAdapter implements ResourceDescriptorProvider, DomainAccessConditionProvider {
    private final AssistantStore store;

    public AssistantAccessAdapter(AssistantStore store) {
        this.store = store;
    }

    @Override
    public boolean supports(ResourceType type) {
        return ASSISTANT.equals(type);
    }

    @Override
    public boolean supports(ResourceType type, Action action) {
        return supports(type);
    }

    @Override
    public Optional<ResourceDescriptor> find(ResourceRef ref) {
        return store.find(ref, false).map(Resource::descriptor);
    }

    @Override
    public Optional<ResourceDescriptor> findCurrent(ResourceRef ref) {
        return store.find(ref, true).map(Resource::descriptor);
    }

    @Override
    public AccessReasonEnum evaluate(TrustedActor actor, AccessRequest request, ResourceDescriptor descriptor, VerifiedScope scope) {
        if (!request.scope().workspaceId().equals(descriptor.ownership().workspaceId())) {
            return AccessReasonEnum.DOMAIN_DENIED;
        }
        var action = request.action();
        if (ASSISTANT_SHARE.equals(action) || RESOURCE_ACCESS_MANAGE.equals(action)) {
            return AccessReasonEnum.DOMAIN_DENIED;
        }
        if (ASSISTANT_USE.equals(action) && State.DISABLED.name().equals(descriptor.domainState())) {
            return AccessReasonEnum.DOMAIN_DENIED;
        }
        return AccessReasonEnum.ALLOWED;
    }

    @Override
    public AccessReasonEnum evaluateCurrent(TrustedActor actor, AccessRequest request, ResourceDescriptor descriptor, VerifiedScope scope) {
        return evaluate(actor, request, descriptor, scope);
    }
}
