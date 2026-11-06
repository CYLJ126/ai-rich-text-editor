package com.arte.core.pojo.workspace;

import com.arte.core.enums.access.GrantTargetKindEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.pojo.access.ResourceType;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 固定结构政策上限，无策略脚本。空白名单不表示全部允许；空间政策只能收紧租户政策，AI 仍校验连接及凭据。
 *
 * @param allowedCreationTypes       允许创建的资源类型集合
 * @param extraCreationRoles         按资源类型额外允许执行创建的空间角色集合
 * @param guestAiAllowed             是否允许空间访客使用 AI
 * @param personalCredentialsAllowed 是否允许使用个人 AI 凭据
 * @param allowedProtocols           允许的 AI 连接协议标识集合
 * @param allowedProviders           允许的 AI 提供方标识集合
 * @param allowedDestinations        允许的 AI 连接目标主机集合
 * @param exportTypes                允许导出的资源类型集合
 * @param egressTypes                允许外发的资源类型集合
 * @param shareTargetKinds           允许的共享授权目标类别集合
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

    /**
     * 返回 AI 连接受策略约束的字段，不检查连接、部署出口或调用资格。
     */
    public List<String> connectionRestrictions(String protocol, String provider, String host) {
        List<String> fields = new ArrayList<>(4);
        if (!personalCredentialsAllowed) {
            fields.add("personalCredentialsAllowed");
        }
        if (!allowedProtocols.contains(protocol)) {
            fields.add("allowedProtocols");
        }
        if (!allowedProviders.contains(provider)) {
            fields.add("allowedProviders");
        }
        if (host == null || !allowedDestinations.contains(host)) {
            fields.add("allowedDestinations");
        }
        return List.copyOf(fields);
    }

}
