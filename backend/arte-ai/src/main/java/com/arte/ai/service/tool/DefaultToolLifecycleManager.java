package com.arte.ai.service.tool;

import com.arte.ai.api.tool.*;
import com.arte.ai.common.enums.tool.ToolClusterEventTypeEnum;
import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;
import com.arte.ai.mapper.tool.ToolMapper;
import com.arte.ai.mapper.tool.ToolVersionMapper;
import com.arte.ai.pojo.tool.ToolReference;
import com.arte.ai.pojo.tool.po.ToolPo;
import com.arte.ai.pojo.tool.po.ToolVersionPo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 默认工具发布生命周期服务
 * <p>
 * 发布前再次校验运行时定义，数据库提交成功后才加入注册表，避免调用方看到未提交的版本。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Service
public class DefaultToolLifecycleManager implements ToolLifecycleManager {

    private final Map<String, ToolProvider> providers;
    private final ToolMapper toolMapper;
    private final ToolVersionMapper versionMapper;
    private final ToolDefinitionValidator definitionValidator;
    private final ToolRegistry registry;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<ToolClusterEventPublisher> eventPublisherProvider;

    public DefaultToolLifecycleManager(List<ToolProvider> providers,
                                       ToolMapper toolMapper,
                                       ToolVersionMapper versionMapper,
                                       ToolDefinitionValidator definitionValidator,
                                       ToolRegistry registry,
                                       TransactionTemplate transactionTemplate,
                                       ObjectProvider<ToolClusterEventPublisher> eventPublisherProvider) {
        this.providers = indexProviders(providers);
        this.toolMapper = toolMapper;
        this.versionMapper = versionMapper;
        this.definitionValidator = definitionValidator;
        this.registry = registry;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisherProvider = eventPublisherProvider;
    }

    @Override
    public void publish(ToolReference reference) {
        CatalogEntry entry = requireEntry(reference);
        ToolProvider provider = requireProvider(entry.tool().getProviderId());
        Tool<?, ?> runtimeTool = provider.resolve(reference)
                .orElseThrow(() -> new IllegalStateException(
                        "tool is not loaded by its provider; synchronize the provider first: " + reference));
        definitionValidator.validate(runtimeTool.getDefinition());

        transactionTemplate.executeWithoutResult(status -> {
            ToolVersionPo current = versionMapper.selectExact(entry.tool().getToolId(), reference.version())
                    .orElseThrow(() -> new IllegalArgumentException("unknown tool version: " + reference));
            if (current.getLifecycleState() == ToolLifecycleStateEnum.DRAFT) {
                int updated = versionMapper.publish(current.getId(), current.getRowVersion(), LocalDateTime.now());
                if (updated != 1) {
                    throw new IllegalStateException("tool version changed concurrently: " + reference);
                }
            } else if (current.getLifecycleState() != ToolLifecycleStateEnum.PUBLISHED) {
                throw new IllegalStateException("disabled or deprecated tool versions cannot be published: " + reference);
            }
            ToolPo tool = entry.tool();
            tool.setLifecycleState(ToolLifecycleStateEnum.PUBLISHED);
            tool.setLatestVersion(reference.version());
            toolMapper.updateById(tool);
        });

        registry.unregister(reference);
        registry.register(entry.tool().getProviderId(), runtimeTool);
        publishToolEvent(entry.tool().getProviderId(), reference, ToolClusterEventTypeEnum.TOOL_PUBLISHED);
    }

    @Override
    public void deprecate(ToolReference reference, String reason) {
        String providerId = changeState(reference, ToolLifecycleStateEnum.DEPRECATED);
        publishToolEvent(providerId, reference, ToolClusterEventTypeEnum.TOOL_DEPRECATED);
    }

    @Override
    public void disable(ToolReference reference, String reason) {
        String providerId = changeState(reference, ToolLifecycleStateEnum.DISABLED);
        publishToolEvent(providerId, reference, ToolClusterEventTypeEnum.TOOL_DISABLED);
    }

    @Override
    public Optional<ToolLifecycleStateEnum> getState(ToolReference reference) {
        if (reference == null) {
            return Optional.empty();
        }
        return toolMapper.selectByIdentity(reference.namespace(), reference.name())
                .flatMap(tool -> versionMapper.selectExact(tool.getToolId(), reference.version()))
                .map(ToolVersionPo::getLifecycleState);
    }

    private String changeState(ToolReference reference, ToolLifecycleStateEnum targetState) {
        CatalogEntry entry = requireEntry(reference);
        transactionTemplate.executeWithoutResult(status -> {
            ToolVersionPo version = versionMapper.selectExact(entry.tool().getToolId(), reference.version())
                    .orElseThrow(() -> new IllegalArgumentException("unknown tool version: " + reference));
            version.setLifecycleState(targetState);
            versionMapper.updateById(version);

            ToolPo tool = entry.tool();
            if (reference.version().equals(tool.getLatestVersion())) {
                ToolVersionPo latestPublished = versionMapper.selectLatestPublished(tool.getToolId()).orElse(null);
                if (latestPublished == null || latestPublished.getVersion().equals(reference.version())) {
                    tool.setLatestVersion(null);
                    tool.setLifecycleState(targetState);
                } else {
                    tool.setLatestVersion(latestPublished.getVersion());
                    tool.setLifecycleState(ToolLifecycleStateEnum.PUBLISHED);
                }
                toolMapper.updateById(tool);
            }
        });
        registry.unregister(reference);
        return entry.tool().getProviderId();
    }

    private CatalogEntry requireEntry(ToolReference reference) {
        if (reference == null) {
            throw new IllegalArgumentException("reference must not be null");
        }
        ToolPo tool = toolMapper.selectByIdentity(reference.namespace(), reference.name())
                .orElseThrow(() -> new IllegalArgumentException("unknown tool: " + reference));
        ToolVersionPo version = versionMapper.selectExact(tool.getToolId(), reference.version())
                .orElseThrow(() -> new IllegalArgumentException("unknown tool version: " + reference));
        return new CatalogEntry(tool, version);
    }

    private ToolProvider requireProvider(String providerId) {
        ToolProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalStateException("tool provider is not loaded: " + providerId);
        }
        return provider;
    }

    private Map<String, ToolProvider> indexProviders(List<ToolProvider> providers) {
        Map<String, ToolProvider> result = new HashMap<>();
        for (ToolProvider provider : providers) {
            if (result.putIfAbsent(provider.getProviderId(), provider) != null) {
                throw new IllegalStateException("duplicate tool provider id: " + provider.getProviderId());
            }
        }
        return Map.copyOf(result);
    }

    private void publishToolEvent(String providerId, ToolReference reference,
                                  ToolClusterEventTypeEnum eventType) {
        ToolClusterEventPublisher publisher = eventPublisherProvider.getIfAvailable();
        if (publisher != null) {
            publisher.publishToolChanged(providerId, reference, eventType);
        }
    }

    private record CatalogEntry(ToolPo tool, ToolVersionPo version) {
    }
}
