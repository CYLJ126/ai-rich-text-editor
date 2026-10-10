package com.arte.app.platform.workspace;

import com.arte.core.enums.access.GrantTargetKindEnum;
import com.arte.core.pojo.access.ResourceType;
import com.arte.core.pojo.workspace.PolicyRules;
import com.arte.core.utils.platform.PlatformChecks;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 新个人租户的政策模板；只影响新建记录，不覆盖已保存政策。
 * 空集合明确表示禁止，不解释为通配符。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 18:22 ✾
 */
@ConfigurationProperties(prefix = "arte.platform.workspace", ignoreUnknownFields = false)
public record PlatformWorkspaceProperties(boolean enabled, PersonalPolicy personalPolicy) {

    public PlatformWorkspaceProperties {
        personalPolicy = personalPolicy == null ? new PersonalPolicy(null, null, null, null, null, null, null, null) : personalPolicy;
    }

    public record PersonalPolicy(Set<String> creationTypes, Boolean personalCredentialsAllowed,
                                 Set<String> protocols, Set<String> providers, Set<String> destinations,
                                 Set<String> exportTypes, Set<String> egressTypes,
                                 Set<GrantTargetKindEnum> shareTargetKinds) {
        private static final Set<String> TYPES = Set.of("article", "collection", "model", "assistant");

        public PersonalPolicy {
            creationTypes = types(creationTypes, TYPES);
            personalCredentialsAllowed = personalCredentialsAllowed == null || personalCredentialsAllowed;
            protocols = whitelist(protocols);
            providers = whitelist(providers);
            destinations = whitelist(destinations);
            exportTypes = types(exportTypes, Set.of("article", "collection"));
            egressTypes = types(egressTypes, Set.of("article", "collection"));
            shareTargetKinds = shareTargetKinds == null
                    ? Set.of(GrantTargetKindEnum.SUBJECT, GrantTargetKindEnum.SPACE_ROLE, GrantTargetKindEnum.WORKSPACE)
                    : Set.copyOf(shareTargetKinds);
        }

        public PolicyRules rules() {
            return new PolicyRules(resources(creationTypes), Map.of(), false, personalCredentialsAllowed,
                    protocols, providers, destinations, resources(exportTypes), resources(egressTypes), shareTargetKinds);
        }

        private static Set<String> types(Set<String> values, Set<String> defaults) {
            var result = values == null ? defaults : Set.copyOf(values);
            if (!TYPES.containsAll(result)) throw new IllegalArgumentException("Unsupported workspace resource type");
            return result;
        }

        private static Set<String> whitelist(Set<String> values) {
            var result = values == null ? Set.<String>of() : Set.copyOf(values);
            if (result.size() > 100) throw new IllegalArgumentException("Workspace whitelist exceeds 100 entries");
            result.forEach(v -> PlatformChecks.id(v, "workspace whitelist entry"));
            return result;
        }

        private static Set<ResourceType> resources(Set<String> values) {
            return values.stream().map(ResourceType::new).collect(Collectors.toUnmodifiableSet());
        }
    }
}
