package com.arte.ainew.pojo.model;

import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Map;
import java.util.Set;

/**
 * 租户模型范围及独立凭据用途；成员代次不进入公开视图。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/01 19:25 ✾
 */
public record ModelSharingPolicy(ResourceRef resource, Scope managementScope, Set<String> workspaceIds,
                                 long credentialVersion, Map<String, Long> consumers, long version) {

    public ModelSharingPolicy {
        workspaceIds = Set.copyOf(workspaceIds);
        consumers = Map.copyOf(consumers);
        PlatformChecks.require(resource.tenantId().equals(managementScope.tenantId()) && resource.type().value().equals("model")
                && version >= 0 && credentialVersion >= 0 && workspaceIds.size() <= 256 && consumers.size() <= 256, "Invalid model sharing policy");
        PlatformChecks.require(consumers.isEmpty() || credentialVersion >= 1, "Consumers require an exact credential generation");
        workspaceIds.forEach(id -> PlatformChecks.id(id, "workspaceId"));
        consumers.forEach((id, generation) -> {
            PlatformChecks.id(id, "subjectId");
            PlatformChecks.require(generation >= 0, "Invalid consumer generation");
        });
    }

    public static ModelSharingPolicy initial(ModelResource model) {
        return new ModelSharingPolicy(model.resource(), model.managementScope(), Set.of(model.managementScope().workspaceId()), 0, Map.of(), 0);
    }
}
