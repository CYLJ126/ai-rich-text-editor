package com.arte.ai.service.tool;

import com.arte.ai.api.tool.*;
import com.arte.ai.common.enums.tool.ToolClusterEventTypeEnum;
import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;
import com.arte.ai.mapper.tool.ToolMapper;
import com.arte.ai.mapper.tool.ToolVersionMapper;
import com.arte.ai.pojo.tool.ToolProviderSyncResult;
import com.arte.ai.pojo.tool.ToolPublishCommand;
import com.arte.ai.pojo.tool.ToolReference;
import com.arte.ai.pojo.tool.ToolVersionView;
import com.arte.ai.pojo.tool.po.ToolPo;
import com.arte.ai.pojo.tool.po.ToolVersionPo;
import com.arte.ai.service.tool.cluster.ToolDistributedLockExecutor;
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
    private final ToolDistributedLockExecutor lockExecutor;
    private final ToolProviderManager providerManager;
    private final ToolVersionCompatibilityService compatibility;

    public DefaultToolLifecycleManager(List<ToolProvider> providers,
                                       ToolMapper toolMapper,
                                       ToolVersionMapper versionMapper,
                                       ToolDefinitionValidator definitionValidator,
                                       ToolRegistry registry,
                                       TransactionTemplate transactionTemplate,
                                       ObjectProvider<ToolClusterEventPublisher> eventPublisherProvider,
                                       ToolDistributedLockExecutor lockExecutor,
                                       ToolProviderManager providerManager,
                                       ToolVersionCompatibilityService compatibility) {
        this.providers = indexProviders(providers);
        this.toolMapper = toolMapper;
        this.versionMapper = versionMapper;
        this.definitionValidator = definitionValidator;
        this.registry = registry;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisherProvider = eventPublisherProvider;
        this.lockExecutor = lockExecutor;
        this.providerManager = providerManager;
        this.compatibility = compatibility;
    }

    @Override
    public void publish(ToolReference reference) {
        publish(reference, null);
    }

    @Override
    public void publish(ToolReference reference, ToolPublishCommand command) {
        ToolPublishCommand release = command == null ? new ToolPublishCommand(null, null) : command;
        lockExecutor.execute(versionLock(reference), () -> publishLocked(reference, release));
    }

    private void publishLocked(ToolReference reference, ToolPublishCommand release) {
        CatalogEntry entry = requireEntry(reference);
        ToolProviderSyncResult syncResult = providerManager.synchronize(entry.tool().getProviderId())
                .toCompletableFuture().join();
        if (!syncResult.succeeded()) {
            throw new IllegalStateException("tool provider synchronization failed before publication: "
                    + syncResult.errorMessage());
        }
        // 同步可能更新草稿定义快照和 row_version，因此发布前重新读取。
        CatalogEntry synchronizedEntry = requireEntry(reference);
        ToolProvider provider = requireProvider(synchronizedEntry.tool().getProviderId());
        Tool<?, ?> runtimeTool = provider.resolve(reference)
                .orElseThrow(() -> new IllegalStateException(
                        "tool is not loaded by its provider; synchronize the provider first: " + reference));
        definitionValidator.validate(runtimeTool.getDefinition());

        transactionTemplate.executeWithoutResult(status -> {
            ToolVersionPo current = versionMapper.selectExact(synchronizedEntry.tool().getToolId(), reference.version())
                    .orElseThrow(() -> new IllegalArgumentException("unknown tool version: " + reference));
            if (release.compatibilityBaseVersion() != null) {
                ToolVersionPo base = versionMapper.selectExact(current.getToolId(), release.compatibilityBaseVersion())
                        .orElseThrow(() -> new IllegalArgumentException("兼容基准版本不存在"));
                compatibility.requireReleaseBaseline(base, current);
                if (release.releaseNotes() == null) throw new IllegalArgumentException("兼容升级必须填写发布说明");
                compatibility.requireCompatible(base, current);
            }
            if (current.getLifecycleState() == ToolLifecycleStateEnum.DRAFT) {
                int updated = versionMapper.publish(current.getId(), current.getRowVersion(), LocalDateTime.now(),
                        release.compatibilityBaseVersion(), release.releaseNotes());
                if (updated != 1) {
                    throw new IllegalStateException("tool version changed concurrently: " + reference);
                }
                ToolPo tool = synchronizedEntry.tool();
                tool.setLifecycleState(ToolLifecycleStateEnum.PUBLISHED);
                tool.setLatestVersion(reference.version());
                toolMapper.updateById(tool);
            } else if (current.getLifecycleState() == ToolLifecycleStateEnum.PUBLISHED && release.compatibilityBaseVersion() != null) {
                if (current.getCompatibilityBaseVersion() == null) {
                    int updated = versionMapper.declareCompatibility(current.getId(), current.getRowVersion(),
                            release.compatibilityBaseVersion(), release.releaseNotes());
                    if (updated != 1) throw new IllegalStateException("兼容升级关系已被其他操作修改，请刷新页面");
                } else if (!current.getCompatibilityBaseVersion().equals(release.compatibilityBaseVersion())
                        || !java.util.Objects.equals(current.getReleaseNotes(), release.releaseNotes())) {
                    throw new IllegalStateException("已确认的兼容升级关系不可改写，请使用新版本");
                }
            } else if (current.getLifecycleState() != ToolLifecycleStateEnum.PUBLISHED) {
                throw new IllegalStateException("disabled or deprecated tool versions cannot be published: " + reference);
            }
        });

        providerManager.reconcileLocal(synchronizedEntry.tool().getProviderId()).toCompletableFuture().join();
        publishToolEvent(synchronizedEntry.tool().getProviderId(), reference, ToolClusterEventTypeEnum.TOOL_PUBLISHED);
    }

    @Override
    public void deprecate(ToolReference reference, String reason) {
        String providerId = lockExecutor.execute(versionLock(reference),
                () -> changeState(reference, ToolLifecycleStateEnum.DEPRECATED));
        publishToolEvent(providerId, reference, ToolClusterEventTypeEnum.TOOL_DEPRECATED);
    }

    @Override
    public void disable(ToolReference reference, String reason) {
        String providerId = lockExecutor.execute(versionLock(reference),
                () -> changeState(reference, ToolLifecycleStateEnum.DISABLED));
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

    @Override
    public List<ToolVersionView> listVersions(String namespace, String name) {
        ToolPo tool = toolMapper.selectByIdentity(namespace, name)
                .orElseThrow(() -> new IllegalArgumentException("unknown tool: " + namespace + ":" + name));
        return versionMapper.selectVersions(tool.getToolId()).stream()
                .map(version -> new ToolVersionView(
                        new ToolReference(tool.getNamespace(), tool.getName(), version.getVersion()),
                        tool.getToolId(), version.getTitle(), version.getDescription(),
                        version.getLifecycleState(), version.getChecksum(), version.getRowVersion(),
                        version.getPublishedAt(), version.getCompatibilityBaseVersion(), version.getReleaseNotes()))
                .toList();
    }

    private String changeState(ToolReference reference, ToolLifecycleStateEnum targetState) {
        CatalogEntry entry = requireEntry(reference);
        transactionTemplate.executeWithoutResult(status -> {
            ToolVersionPo version = versionMapper.selectExact(entry.tool().getToolId(), reference.version())
                    .orElseThrow(() -> new IllegalArgumentException("unknown tool version: " + reference));
            ToolLifecycleStateEnum currentState = version.getLifecycleState();
            if (currentState == targetState) {
                return;
            }
            if (targetState == ToolLifecycleStateEnum.DEPRECATED
                    && currentState != ToolLifecycleStateEnum.PUBLISHED) {
                throw new IllegalStateException("only a published tool version can be deprecated: " + reference);
            }
            int updated = versionMapper.transition(version.getId(), version.getRowVersion(),
                    currentState, targetState);
            if (updated != 1) {
                throw new IllegalStateException("tool version changed concurrently: " + reference);
            }

            refreshCatalogState(entry.tool(), targetState);
        });
        registry.unregister(reference);
        return entry.tool().getProviderId();
    }

    private void refreshCatalogState(ToolPo tool, ToolLifecycleStateEnum fallbackState) {
        ToolVersionPo latestPublished = versionMapper.selectLatestPublished(tool.getToolId()).orElse(null);
        if (latestPublished != null) {
            tool.setLatestVersion(latestPublished.getVersion());
            tool.setLifecycleState(ToolLifecycleStateEnum.PUBLISHED);
        } else {
            boolean hasDraft = versionMapper.selectVersions(tool.getToolId()).stream()
                    .anyMatch(version -> version.getLifecycleState() == ToolLifecycleStateEnum.DRAFT);
            tool.setLatestVersion(null);
            tool.setLifecycleState(hasDraft ? ToolLifecycleStateEnum.DRAFT : fallbackState);
        }
        toolMapper.updateById(tool);
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

    private String versionLock(ToolReference reference) {
        if (reference == null) {
            throw new IllegalArgumentException("reference must not be null");
        }
        return "version:" + reference.namespace() + ":" + reference.name() + ":" + reference.version();
    }

    private record CatalogEntry(ToolPo tool, ToolVersionPo version) {
    }
}
