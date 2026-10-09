package com.arte.core.pojo.workspace;

import com.arte.core.enums.access.GrantTargetKindEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.pojo.access.ResourceType;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Map;
import java.util.Set;

/**
 * 固定结构政策上限，无策略脚本。空白名单不表示全部允许；空间政策只能收紧租户政策，AI 仍校验连接及凭据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record PolicyRules(
        Set<ResourceType> allowedCreationTypes,
        Map<ResourceType, Set<WorkspaceRoleEnum>> extraCreationRoles,
        boolean guestAiAllowed,
        boolean personalCredentialsAllowed,
        Set<String> allowedProtocols,
        Set<String> allowedProviders,
        Set<String> allowedDestinations,
        Set<ResourceType> exportTypes,
        Set<ResourceType> egressTypes,
        Set<GrantTargetKindEnum> shareTargetKinds) {
    public PolicyRules {
        allowedCreationTypes = Set.copyOf(allowedCreationTypes);
        extraCreationRoles = PlatformChecks.copySetMap(extraCreationRoles);
        allowedProtocols = Set.copyOf(allowedProtocols);
        allowedProviders = Set.copyOf(allowedProviders);
        allowedDestinations = Set.copyOf(allowedDestinations);
        exportTypes = Set.copyOf(exportTypes);
        egressTypes = Set.copyOf(egressTypes);
        shareTargetKinds = Set.copyOf(shareTargetKinds);
        PlatformChecks.require(allowedCreationTypes.containsAll(extraCreationRoles.keySet()), "extra creation roles require allowed resource type");
        allowedProtocols.forEach(v -> PlatformChecks.id(v, "protocol"));
        allowedProviders.forEach(v -> PlatformChecks.id(v, "provider"));
        allowedDestinations.forEach(v -> PlatformChecks.id(v, "destination"));
    }
}
