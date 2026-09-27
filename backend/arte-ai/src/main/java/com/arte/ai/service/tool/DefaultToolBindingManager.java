package com.arte.ai.service.tool;

import com.arte.ai.api.tool.ToolAvailabilityService;
import com.arte.ai.api.tool.ToolBindingManager;
import com.arte.ai.api.tool.ToolConfigurationMerger;
import com.arte.ai.api.tool.ToolPolicyMerger;
import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;
import com.arte.ai.mapper.tool.ToolBindingMapper;
import com.arte.ai.mapper.tool.ToolMapper;
import com.arte.ai.mapper.tool.ToolProviderMapper;
import com.arte.ai.mapper.tool.ToolVersionMapper;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.pojo.tool.po.ToolBindingPo;
import com.arte.ai.pojo.tool.po.ToolPo;
import com.arte.ai.pojo.tool.po.ToolProviderPo;
import com.arte.ai.pojo.tool.po.ToolVersionPo;
import com.arte.ai.service.tool.cluster.ToolDistributedLockExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 基于数据库的用户/工作空间工具绑定管理服务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
@Service
@RequiredArgsConstructor
public class DefaultToolBindingManager implements ToolBindingManager {

    private static final String PROVIDER_ENABLED = "enabled";
    private static final Pattern SENSITIVE_CONFIGURATION_KEY = Pattern.compile(
            "(?i).*(password|secret|token|api[-_]?key|private[-_]?key|credential).*");

    private final ToolBindingMapper bindingMapper;
    private final ToolMapper toolMapper;
    private final ToolVersionMapper versionMapper;
    private final ToolProviderMapper providerMapper;
    private final ToolPolicyMerger policyMerger;
    private final ToolConfigurationMerger configurationMerger;
    private final ToolAvailabilityService availabilityService;
    private final ToolDistributedLockExecutor lockExecutor;
    private final TransactionTemplate transactionTemplate;

    @Override
    public ResolvedToolBinding save(String ownerId, ToolBindingCommand command) {
        String normalizedOwner = requireText(ownerId, "ownerId");
        String workspaceId = normalize(command.workspaceId());
        validateConfiguration(command.configuration());
        if (normalize(command.credentialReference()) != null
                && command.credentialReference().trim().length() > 128) {
            throw new IllegalArgumentException("credentialReference must not exceed 128 characters");
        }
        CatalogVersion catalog = requirePublished(command.tool());
        String lockResource = bindingLock(normalizedOwner, workspaceId, catalog.tool(), command.tool());
        return lockExecutor.execute(lockResource, () -> transactionTemplate.execute(status ->
                saveLocked(normalizedOwner, workspaceId, catalog, command)));
    }

    @Override
    public Optional<ResolvedToolBinding> resolve(String ownerId, String workspaceId, String bindingId) {
        String normalizedOwner = requireText(ownerId, "ownerId");
        String normalizedWorkspace = normalize(workspaceId);
        return bindingMapper.selectOwned(normalizedOwner, requireText(bindingId, "bindingId"))
                .filter(ToolBindingPo::getEnabled)
                .filter(binding -> workspaceMatches(normalizedWorkspace, binding.getWorkspaceId()))
                .map(this::resolveBinding);
    }

    @Override
    public List<ResolvedToolBinding> listEnabled(String ownerId, String workspaceId) {
        String normalizedOwner = requireText(ownerId, "ownerId");
        String normalizedWorkspace = normalize(workspaceId);
        return bindingMapper.selectEnabledByScope(normalizedOwner, normalizedWorkspace).stream()
                .map(this::resolveBinding)
                .toList();
    }

    @Override
    public List<ToolBindingView> list(String ownerId, String workspaceId) {
        String normalizedOwner = requireText(ownerId, "ownerId");
        String normalizedWorkspace = normalize(workspaceId);
        return bindingMapper.selectByOwnerScope(normalizedOwner, normalizedWorkspace).stream()
                .map(this::toView)
                .toList();
    }

    private ResolvedToolBinding saveLocked(String ownerId, String workspaceId,
                                           CatalogVersion catalog, ToolBindingCommand command) {
        ToolBindingPo scoped = bindingMapper.selectByScope(ownerId, workspaceId,
                catalog.tool().getToolId(), command.tool().version()).orElse(null);
        ToolBindingPo binding = command.bindingId() == null || command.bindingId().isBlank()
                ? scoped
                : bindingMapper.selectOwned(ownerId, command.bindingId().trim()).orElse(null);

        if (binding == null) {
            if (scoped != null) {
                throw new IllegalStateException("a binding already exists for this user/workspace and tool version");
            }
            binding = new ToolBindingPo()
                    .setBindingId(command.bindingId() == null || command.bindingId().isBlank()
                            ? UUID.randomUUID().toString() : command.bindingId().trim())
                    .setOwnerId(ownerId)
                    .setWorkspaceId(workspaceId)
                    .setToolId(catalog.tool().getToolId())
                    .setToolVersion(command.tool().version())
                    .setCredentialReference(normalize(command.credentialReference()))
                    .setConfiguration(command.configuration())
                    .setPolicyOverride(policyMerger.encodeOverride(command.policyOverride()))
                    .setEnabled(command.enabled() == null || command.enabled())
                    .setRowVersion(0L);
            binding.setCreateBy(ownerId);
            binding.setUpdateBy(ownerId);
            validatePolicy(catalog.version(), binding.getPolicyOverride());
            bindingMapper.insert(binding);
            return resolveBinding(binding);
        }

        requireSameTarget(binding, workspaceId, catalog.tool().getToolId(), command.tool().version());
        if (command.expectedRowVersion() == null) {
            throw new IllegalArgumentException("expectedRowVersion is required when updating a binding");
        }
        binding.setCredentialReference(normalize(command.credentialReference()));
        binding.setConfiguration(command.configuration());
        binding.setPolicyOverride(policyMerger.encodeOverride(command.policyOverride()));
        binding.setEnabled(command.enabled() == null ? binding.getEnabled() : command.enabled());
        binding.setUpdateBy(ownerId);
        validatePolicy(catalog.version(), binding.getPolicyOverride());
        int updated = bindingMapper.updateWithVersion(binding, command.expectedRowVersion());
        if (updated != 1) {
            throw new IllegalStateException("tool binding changed concurrently: " + binding.getBindingId());
        }
        binding.setRowVersion(command.expectedRowVersion() + 1);
        return resolveBinding(binding);
    }

    private ResolvedToolBinding resolveBinding(ToolBindingPo binding) {
        ToolPo tool = toolMapper.selectByToolId(binding.getToolId())
                .orElseThrow(() -> new IllegalStateException(
                        "bound tool no longer exists: " + binding.getToolId()));
        ToolReference reference = new ToolReference(tool.getNamespace(), tool.getName(), binding.getToolVersion());
        CatalogVersion catalog = requirePublished(reference);
        ToolProviderPo provider = requireEnabledProvider(tool.getProviderId());
        ToolExecutionPolicy effectivePolicy = policyMerger.tighten(
                policyMerger.decodePolicy(catalog.version().getDefaultPolicy()),
                policyMerger.decodeOverride(binding.getPolicyOverride()));
        Map<String, Object> effectiveConfiguration = configurationMerger.merge(
                catalog.version().getDefaultConfiguration(), binding.getConfiguration());
        String credentialReference = firstNonBlank(binding.getCredentialReference(),
                provider.getCredentialReference());
        return new ResolvedToolBinding(binding.getBindingId(), binding.getOwnerId(),
                normalize(binding.getWorkspaceId()), provider.getProviderId(), tool.getToolId(), reference,
                effectiveConfiguration, credentialReference, effectivePolicy,
                Boolean.TRUE.equals(binding.getEnabled()), binding.getRowVersion());
    }

    private ToolBindingView toView(ToolBindingPo binding) {
        ToolPo tool = toolMapper.selectByToolId(binding.getToolId())
                .orElseThrow(() -> new IllegalStateException(
                        "bound tool no longer exists: " + binding.getToolId()));
        ToolReference reference = new ToolReference(tool.getNamespace(), tool.getName(),
                binding.getToolVersion());
        return new ToolBindingView(binding.getBindingId(), normalize(binding.getWorkspaceId()),
                reference, binding.getCredentialReference(), binding.getConfiguration(),
                policyMerger.decodeOverride(binding.getPolicyOverride()),
                Boolean.TRUE.equals(binding.getEnabled()), availabilityService.isAvailable(reference),
                binding.getRowVersion());
    }

    private CatalogVersion requirePublished(ToolReference reference) {
        ToolPo tool = toolMapper.selectByIdentity(reference.namespace(), reference.name())
                .orElseThrow(() -> new IllegalArgumentException("unknown tool: " + reference));
        ToolVersionPo version = versionMapper.selectExact(tool.getToolId(), reference.version())
                .orElseThrow(() -> new IllegalArgumentException("unknown tool version: " + reference));
        if (tool.getLifecycleState() != ToolLifecycleStateEnum.PUBLISHED
                || version.getLifecycleState() != ToolLifecycleStateEnum.PUBLISHED) {
            throw new IllegalStateException("only published tool versions can be bound: " + reference);
        }
        requireEnabledProvider(tool.getProviderId());
        return new CatalogVersion(tool, version);
    }

    private ToolProviderPo requireEnabledProvider(String providerId) {
        ToolProviderPo provider = providerMapper.selectByProviderId(providerId)
                .orElseThrow(() -> new IllegalStateException("tool provider no longer exists: " + providerId));
        if (!PROVIDER_ENABLED.equalsIgnoreCase(provider.getStatus())) {
            throw new IllegalStateException("tool provider is disabled: " + providerId);
        }
        return provider;
    }

    private void validatePolicy(ToolVersionPo version, Map<String, Object> override) {
        policyMerger.tighten(policyMerger.decodePolicy(version.getDefaultPolicy()),
                policyMerger.decodeOverride(override));
    }

    private void requireSameTarget(ToolBindingPo binding, String workspaceId,
                                   String toolId, String toolVersion) {
        if (!workspaceEquals(workspaceId, binding.getWorkspaceId())
                || !toolId.equals(binding.getToolId())
                || !toolVersion.equals(binding.getToolVersion())) {
            throw new IllegalArgumentException("an existing binding cannot change workspace or tool version");
        }
    }

    private boolean workspaceMatches(String requested, String bound) {
        String normalizedBound = normalize(bound);
        return normalizedBound == null || normalizedBound.equals(requested);
    }

    private boolean workspaceEquals(String first, String second) {
        return java.util.Objects.equals(normalize(first), normalize(second));
    }

    private String bindingLock(String ownerId, String workspaceId, ToolPo tool, ToolReference reference) {
        return "binding:" + ownerId + ":" + (workspaceId == null ? "personal" : workspaceId)
                + ":" + tool.getToolId() + ":" + reference.version();
    }

    private String firstNonBlank(String first, String second) {
        String normalized = normalize(first);
        return normalized == null ? normalize(second) : normalized;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String requireText(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private void validateConfiguration(Map<String, Object> configuration) {
        if (configuration == null) {
            return;
        }
        configuration.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("configuration keys must not be blank");
            }
            if (SENSITIVE_CONFIGURATION_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException(
                        "sensitive values must use credentialReference instead of configuration: " + key);
            }
            if (value instanceof Map<?, ?> nested) {
                validateConfiguration((Map<String, Object>) nested);
            }
        });
    }

    private record CatalogVersion(ToolPo tool, ToolVersionPo version) {
    }
}
