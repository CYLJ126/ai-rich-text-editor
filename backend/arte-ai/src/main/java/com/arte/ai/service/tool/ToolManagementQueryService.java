package com.arte.ai.service.tool;

import com.arte.ai.api.tool.ToolProvider;
import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;
import com.arte.ai.mapper.tool.ToolMapper;
import com.arte.ai.mapper.tool.ToolProviderMapper;
import com.arte.ai.mapper.tool.ToolVersionMapper;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.pojo.tool.po.ToolPo;
import com.arte.ai.pojo.tool.po.ToolProviderPo;
import com.arte.ai.pojo.tool.po.ToolVersionPo;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 提供者、管理态工具目录和版本快照的只读查询服务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Service
public class ToolManagementQueryService {

    private static final String STATUS_NOT_SYNCHRONIZED = "not-synchronized";
    private final Map<String, ToolProvider> runtimeProviders;
    private final ToolProviderMapper providerMapper;
    private final ToolMapper toolMapper;
    private final ToolVersionMapper versionMapper;

    public ToolManagementQueryService(List<ToolProvider> providers,
                                      ToolProviderMapper providerMapper,
                                      ToolMapper toolMapper,
                                      ToolVersionMapper versionMapper) {
        Map<String, ToolProvider> indexedProviders = providers.stream().collect(Collectors.toMap(
                ToolProvider::getProviderId, Function.identity(),
                (left, right) -> {
                    throw new IllegalStateException("duplicate tool provider id: " + left.getProviderId());
                }, LinkedHashMap::new));
        this.runtimeProviders = Map.copyOf(indexedProviders);
        this.providerMapper = providerMapper;
        this.toolMapper = toolMapper;
        this.versionMapper = versionMapper;
    }

    public List<ToolProviderView> listProviders(String status, String providerType) {
        Map<String, ToolProviderPo> persisted = providerMapper.selectList(null).stream()
                .collect(Collectors.toMap(ToolProviderPo::getProviderId, Function.identity()));
        Set<String> providerIds = new TreeSet<>(persisted.keySet());
        providerIds.addAll(runtimeProviders.keySet());
        return providerIds.stream()
                .map(providerId -> providerView(providerId, persisted.get(providerId)))
                .filter(view -> status == null || status.isBlank()
                        || status.equalsIgnoreCase(view.status()))
                .filter(view -> providerType == null || providerType.isBlank()
                        || providerType.equalsIgnoreCase(view.providerType()))
                .toList();
    }

    public Optional<ToolProviderView> findProvider(String providerId) {
        ToolProviderPo persisted = providerMapper.selectByProviderId(providerId).orElse(null);
        if (persisted == null && !runtimeProviders.containsKey(providerId)) {
            return Optional.empty();
        }
        return Optional.of(providerView(providerId, persisted));
    }

    public ToolCatalogPage catalog(String keyword, String providerId,
                                   ToolLifecycleStateEnum lifecycleState,
                                   int current, int size) {
        int safeCurrent = Math.max(1, current);
        int safeSize = Math.max(1, Math.min(size, 200));
        long offset = (long) (safeCurrent - 1) * safeSize;
        List<ToolCatalogItem> records = toolMapper.selectCatalog(keyword, providerId,
                lifecycleState, offset, safeSize);
        long total = toolMapper.countCatalog(keyword, providerId, lifecycleState);
        return new ToolCatalogPage(records, total, safeCurrent, safeSize);
    }

    public Optional<ToolVersionDetailView> findVersion(ToolReference reference) {
        return toolMapper.selectByIdentity(reference.namespace(), reference.name())
                .flatMap(tool -> versionMapper.selectExact(tool.getToolId(), reference.version())
                        .map(version -> versionView(tool, version)));
    }

    private ToolProviderView providerView(String providerId, ToolProviderPo persisted) {
        ToolProvider runtime = runtimeProviders.get(providerId);
        String name = runtime != null ? runtime.getName() : persisted.getName();
        String type = runtime != null ? runtime.getProviderType().getValue() : persisted.getProviderType();
        String endpoint = runtime != null ? runtime.getEndpoint().orElse(null) : persisted.getEndpoint();
        Map<String, Object> configuration = runtime != null
                ? runtime.getConfiguration() : persisted.getConfig();
        return new ToolProviderView(providerId, name, type, endpoint, configuration,
                persisted == null ? STATUS_NOT_SYNCHRONIZED : persisted.getStatus(),
                runtime != null, runtime != null && runtime.supportsStartupRefresh(),
                toolMapper.countByProviderId(providerId),
                persisted == null ? null : persisted.getLastSyncTime(),
                persisted == null ? null : persisted.getLastError());
    }

    private ToolVersionDetailView versionView(ToolPo tool, ToolVersionPo version) {
        return new ToolVersionDetailView(
                new ToolReference(tool.getNamespace(), tool.getName(), version.getVersion()),
                tool.getToolId(), tool.getProviderId(), version.getTitle(), version.getDescription(),
                version.getInputSchema(), version.getOutputSchema(), version.getCapabilities(),
                version.getRiskProfile(), version.getDefaultConfiguration(), version.getDefaultPolicy(),
                version.getTags(), version.getChecksum(), version.getLifecycleState(),
                version.getRowVersion(), version.getPublishedAt(), version.getCreateTime(),
                version.getUpdateTime());
    }
}
