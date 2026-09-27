package com.arte.ai.service.tool;

import com.arte.ai.api.tool.*;
import com.arte.ai.common.enums.tool.ToolClusterEventTypeEnum;
import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;
import com.arte.ai.config.ToolClusterProperties;
import com.arte.ai.mapper.tool.ToolMapper;
import com.arte.ai.mapper.tool.ToolProviderMapper;
import com.arte.ai.mapper.tool.ToolVersionMapper;
import com.arte.ai.pojo.tool.ToolDefinition;
import com.arte.ai.pojo.tool.ToolProviderSyncResult;
import com.arte.ai.pojo.tool.ToolReference;
import com.arte.ai.pojo.tool.ToolSchema;
import com.arte.ai.pojo.tool.po.ToolPo;
import com.arte.ai.pojo.tool.po.ToolProviderPo;
import com.arte.ai.pojo.tool.po.ToolVersionPo;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 默认工具提供者管理服务
 * <p>
 * 提供者刷新成功后才更新数据库目录；新发现的工具版本保持草稿状态，只有已发布版本会进入
 * 运行时注册表。刷新失败不会清空已有工具，只记录失败原因和最后同步时间。</p>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Slf4j
@Service
public class DefaultToolProviderManager implements ToolProviderManager {

    private static final String STATUS_ENABLED = "enabled";
    private static final String STATUS_DISABLED = "disabled";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Map<String, ToolProvider> providers;
    private final Map<String, CompletableFuture<ToolProviderSyncResult>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ToolProviderSyncResult>> reconcileInFlight = new ConcurrentHashMap<>();
    private final ToolProviderMapper providerMapper;
    private final ToolMapper toolMapper;
    private final ToolVersionMapper versionMapper;
    private final ToolDefinitionValidator definitionValidator;
    private final ToolAvailabilityService availabilityService;
    private final ToolRegistry registry;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final ObjectProvider<ToolClusterEventPublisher> eventPublisherProvider;
    private final ToolClusterProperties clusterProperties;
    private final Executor executor;
    private final boolean autoSyncEnabled;

    public DefaultToolProviderManager(List<ToolProvider> providers,
                                      ToolProviderMapper providerMapper,
                                      ToolMapper toolMapper,
                                      ToolVersionMapper versionMapper,
                                      ToolDefinitionValidator definitionValidator,
                                      ToolAvailabilityService availabilityService,
                                      ToolRegistry registry,
                                      TransactionTemplate transactionTemplate,
                                      ObjectMapper objectMapper,
                                      ObjectProvider<RedissonClient> redissonClientProvider,
                                      ObjectProvider<ToolClusterEventPublisher> eventPublisherProvider,
                                      ToolClusterProperties clusterProperties,
                                      @Qualifier("toolCallbackExecutor") Executor executor,
                                      @Value("${arte.ai.tool.auto-sync-enabled:true}") boolean autoSyncEnabled) {
        this.providers = indexProviders(providers);
        this.providerMapper = providerMapper;
        this.toolMapper = toolMapper;
        this.versionMapper = versionMapper;
        this.definitionValidator = definitionValidator;
        this.availabilityService = availabilityService;
        this.registry = registry;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.redissonClientProvider = redissonClientProvider;
        this.eventPublisherProvider = eventPublisherProvider;
        this.clusterProperties = clusterProperties;
        this.executor = executor;
        this.autoSyncEnabled = autoSyncEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void synchronizeOnStartup() {
        if (!autoSyncEnabled || providers.isEmpty()) {
            return;
        }
        List<String> startupProviders = providers.values().stream()
                .filter(ToolProvider::supportsStartupRefresh)
                .map(ToolProvider::getProviderId)
                .sorted()
                .toList();
        synchronizeProviders(startupProviders).whenComplete((results, throwable) -> {
            if (throwable != null) {
                log.error("Failed to synchronize tool providers on startup", unwrap(throwable));
                return;
            }
            results.stream()
                    .filter(result -> !result.succeeded())
                    .forEach(result -> log.warn("Tool provider {} synchronization failed: {}",
                            result.providerId(), result.errorMessage()));
        });
    }

    @Override
    public CompletionStage<ToolProviderSyncResult> synchronize(String providerId) {
        ToolProvider provider = requireProvider(providerId);
        CompletableFuture<ToolProviderSyncResult> pending = new CompletableFuture<>();
        CompletableFuture<ToolProviderSyncResult> existing = inFlight.putIfAbsent(providerId, pending);
        if (existing != null) {
            return existing;
        }

        doSynchronize(provider).whenComplete((result, throwable) -> {
            try {
                if (throwable == null) {
                    pending.complete(result);
                    if (result.succeeded()) {
                        publishProviderEvent(providerId, ToolClusterEventTypeEnum.PROVIDER_SYNCED);
                    }
                } else {
                    pending.complete(recordFailure(provider, unwrap(throwable)));
                }
            } catch (Exception exception) {
                pending.completeExceptionally(exception);
            } finally {
                inFlight.remove(providerId, pending);
            }
        });
        return pending;
    }

    @Override
    public CompletionStage<List<ToolProviderSyncResult>> synchronizeAll() {
        return synchronizeProviders(providers.keySet().stream().sorted().toList());
    }

    @Override
    public CompletionStage<ToolProviderSyncResult> reconcileLocal(String providerId) {
        ToolProvider provider = requireProvider(providerId);
        AtomicReference<CompletableFuture<ToolProviderSyncResult>> scheduled = new AtomicReference<>();
        reconcileInFlight.compute(providerId, (key, previous) -> {
            CompletableFuture<Void> ready = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((ignored, throwable) -> null);
            CompletableFuture<ToolProviderSyncResult> current = ready.thenComposeAsync(
                    ignored -> refreshLocalRegistry(provider), executor);
            scheduled.set(current);
            current.whenComplete((result, throwable) -> reconcileInFlight.remove(key, current));
            return current;
        });
        return scheduled.get();
    }

    @Override
    public CompletionStage<List<ToolProviderSyncResult>> reconcileAllLocal() {
        List<CompletableFuture<ToolProviderSyncResult>> futures = providers.keySet().stream()
                .sorted()
                .map(this::reconcileLocal)
                .map(CompletionStage::toCompletableFuture)
                .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    private CompletionStage<List<ToolProviderSyncResult>> synchronizeProviders(List<String> providerIds) {
        List<CompletableFuture<ToolProviderSyncResult>> futures = providerIds.stream()
                .map(this::synchronize)
                .map(CompletionStage::toCompletableFuture)
                .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    @Override
    public void enable(String providerId) {
        ToolProvider provider = requireProvider(providerId);
        transactionTemplate.executeWithoutResult(status -> {
            ToolProviderPo providerPo = findOrCreateProvider(provider);
            providerPo.setStatus(STATUS_ENABLED);
            providerPo.setLastError(null);
            providerMapper.updateById(providerPo);
        });
        publishProviderEvent(providerId, ToolClusterEventTypeEnum.PROVIDER_ENABLED);
        synchronize(providerId);
    }

    @Override
    public void disable(String providerId, String reason) {
        ToolProvider provider = requireProvider(providerId);
        List<ToolReference> references = transactionTemplate.execute(status -> {
            ToolProviderPo providerPo = findOrCreateProvider(provider);
            providerPo.setStatus(STATUS_DISABLED);
            providerPo.setLastError(null);
            providerMapper.updateById(providerPo);
            return referencesOfProvider(providerId);
        });
        log.info("Tool provider {} disabled: {}", providerId,
                reason == null || reason.isBlank() ? "no reason supplied" : reason.trim());
        if (references != null) {
            references.forEach(registry::unregister);
        }
        publishProviderEvent(providerId, ToolClusterEventTypeEnum.PROVIDER_DISABLED);
    }

    private CompletionStage<ToolProviderSyncResult> doSynchronize(ToolProvider provider) {
        RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
        if (!clusterProperties.isEnabled() || redissonClient == null) {
            return doSynchronizeWithoutLock(provider);
        }
        return CompletableFuture.supplyAsync(() -> synchronizeWithDistributedLock(provider, redissonClient), executor);
    }

    private ToolProviderSyncResult synchronizeWithDistributedLock(ToolProvider provider,
                                                                  RedissonClient redissonClient) {
        RLock lock;
        try {
            lock = redissonClient.getLock(clusterProperties.getSyncLockPrefix() + provider.getProviderId());
        } catch (RuntimeException exception) {
            log.warn("Redis tool synchronization lock is unavailable; falling back to database constraints", exception);
            return doSynchronizeWithoutLock(provider).toCompletableFuture().join();
        }
        boolean acquired;
        try {
            acquired = lock.tryLock(clusterProperties.getSyncLockWaitMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CompletionException(exception);
        } catch (RuntimeException exception) {
            log.warn("Redis tool synchronization lock is unavailable; falling back to database constraints", exception);
            return doSynchronizeWithoutLock(provider).toCompletableFuture().join();
        }
        if (!acquired) {
            return new ToolProviderSyncResult(provider.getProviderId(), false,
                    0, 0, 0, 0, 0, Instant.now(),
                    "another node is synchronizing this provider");
        }
        try {
            return doSynchronizeWithoutLock(provider).toCompletableFuture().join();
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (RuntimeException exception) {
                log.warn("Failed to release tool provider synchronization lock {}", lock.getName(), exception);
            }
        }
    }

    private CompletionStage<ToolProviderSyncResult> doSynchronizeWithoutLock(ToolProvider provider) {
        ToolProviderPo providerPo = transactionTemplate.execute(status -> findOrCreateProvider(provider));
        if (providerPo != null && STATUS_DISABLED.equalsIgnoreCase(providerPo.getStatus())) {
            return CompletableFuture.completedFuture(new ToolProviderSyncResult(
                    provider.getProviderId(), false, 0, 0, 0, 0, 0,
                    Instant.now(), "provider is disabled"));
        }
        try {
            return provider.refresh().thenApply(ignored -> persistSuccessfulRefresh(provider));
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private ToolProviderSyncResult rebuildLocalRegistry(ToolProvider provider) {
        List<ToolDefinition> definitions = List.copyOf(provider.listDefinitions());
        validateUniqueDefinitions(definitions);
        definitions.forEach(definitionValidator::validate);

        List<Tool<?, ?>> availableTools = new ArrayList<>();
        for (ToolDefinition definition : definitions) {
            ToolReference reference = definition.reference();
            if (!availabilityService.isAvailable(reference)) {
                continue;
            }
            availableTools.add(provider.resolve(reference)
                    .orElseThrow(() -> new IllegalStateException(
                            "provider cannot resolve discovered tool: " + reference)));
        }

        registry.unregisterProvider(provider.getProviderId());
        availableTools.forEach(tool -> registry.register(provider.getProviderId(), tool));
        return new ToolProviderSyncResult(provider.getProviderId(), true, definitions.size(),
                0, 0, availableTools.size(), 0, Instant.now(), null);
    }

    private CompletableFuture<ToolProviderSyncResult> refreshLocalRegistry(ToolProvider provider) {
        try {
            return provider.refresh()
                    .thenApply(ignored -> rebuildLocalRegistry(provider))
                    .toCompletableFuture();
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private ToolProviderSyncResult persistSuccessfulRefresh(ToolProvider provider) {
        List<ToolDefinition> definitions = List.copyOf(provider.listDefinitions());
        validateUniqueDefinitions(definitions);
        definitions.forEach(definitionValidator::validate);

        PersistedRefresh persisted = transactionTemplate.execute(status -> persist(provider, definitions));
        if (persisted == null) {
            throw new IllegalStateException("tool provider synchronization transaction returned no result");
        }

        persisted.unregistered().forEach(registry::unregister);
        for (Tool<?, ?> tool : persisted.activated()) {
            ToolReference reference = tool.getDefinition().reference();
            registry.unregister(reference);
            registry.register(provider.getProviderId(), tool);
        }
        return new ToolProviderSyncResult(provider.getProviderId(), true, definitions.size(),
                persisted.createdCount(), persisted.updatedCount(), persisted.activated().size(),
                persisted.disabledCount(), Instant.now(), null);
    }

    private PersistedRefresh persist(ToolProvider provider, List<ToolDefinition> definitions) {
        ToolProviderPo providerPo = findOrCreateProvider(provider);
        if (STATUS_DISABLED.equalsIgnoreCase(providerPo.getStatus())) {
            throw new IllegalStateException("provider was disabled while synchronization was running");
        }
        updateProviderMetadata(providerPo, provider);

        Map<String, ToolPo> existingTools = toolMapper.selectByProviderId(provider.getProviderId()).stream()
                .collect(Collectors.toMap(this::identityKey, Function.identity()));
        Set<String> refreshedIdentities = new HashSet<>();
        Set<ToolReference> refreshedReferences = new HashSet<>();
        List<Tool<?, ?>> activated = new ArrayList<>();
        List<ToolReference> unregistered = new ArrayList<>();
        int created = 0;
        int updated = 0;

        for (ToolDefinition definition : definitions) {
            ToolReference reference = definition.reference();
            String identity = identityKey(reference.namespace(), reference.name());
            refreshedIdentities.add(identity);
            refreshedReferences.add(reference);
            ToolPo toolPo = toolMapper.selectByIdentity(reference.namespace(), reference.name()).orElse(null);
            if (toolPo == null) {
                toolPo = createTool(provider.getProviderId(), definition);
                toolMapper.insert(toolPo);
                existingTools.put(identity, toolPo);
                created++;
            } else {
                if (!provider.getProviderId().equals(toolPo.getProviderId())) {
                    throw new IllegalStateException("tool identity is already owned by provider "
                            + toolPo.getProviderId() + ": " + reference.namespace() + "/" + reference.name());
                }
                toolPo.setTitle(definition.title());
                toolPo.setDescription(definition.description());
                toolMapper.updateById(toolPo);
                updated++;
            }

            ToolVersionPo versionPo = versionMapper.selectExact(toolPo.getToolId(), reference.version()).orElse(null);
            String checksum = checksum(definition);
            if (versionPo == null) {
                versionPo = createVersion(toolPo.getToolId(), definition, checksum);
                versionMapper.insert(versionPo);
                created++;
            } else if (versionPo.getLifecycleState() == ToolLifecycleStateEnum.DRAFT) {
                updateVersion(versionPo, definition, checksum);
                versionMapper.updateById(versionPo);
                updated++;
            } else if (!Objects.equals(versionPo.getChecksum(), checksum)) {
                throw new IllegalStateException("a tool version is immutable after publication: " + reference);
            }

            if (toolPo.getLifecycleState() == ToolLifecycleStateEnum.PUBLISHED
                    && versionPo.getLifecycleState() == ToolLifecycleStateEnum.PUBLISHED) {
                Tool<?, ?> runtimeTool = provider.resolve(reference)
                        .orElseThrow(() -> new IllegalStateException("provider cannot resolve discovered tool: " + reference));
                activated.add(runtimeTool);
            }
        }

        int disabled = 0;
        for (ToolPo staleTool : existingTools.values()) {
            List<ToolVersionPo> persistedVersions = versionMapper.selectVersions(staleTool.getToolId());
            if (!refreshedIdentities.contains(identityKey(staleTool))) {
                if (staleTool.getLifecycleState() != ToolLifecycleStateEnum.DISABLED) {
                    staleTool.setLifecycleState(ToolLifecycleStateEnum.DISABLED);
                    toolMapper.updateById(staleTool);
                    disabled++;
                }
                for (ToolVersionPo version : persistedVersions) {
                    ToolReference reference = new ToolReference(
                            staleTool.getNamespace(), staleTool.getName(), version.getVersion());
                    unregistered.add(reference);
                }
                continue;
            }

            for (ToolVersionPo version : persistedVersions) {
                ToolReference reference = new ToolReference(
                        staleTool.getNamespace(), staleTool.getName(), version.getVersion());
                if (refreshedReferences.contains(reference)) {
                    continue;
                }
                unregistered.add(reference);
            }
        }

        providerPo.setLastSyncTime(LocalDateTime.now());
        providerPo.setLastError(null);
        providerMapper.updateById(providerPo);
        return new PersistedRefresh(created, updated, disabled,
                List.copyOf(activated), List.copyOf(unregistered));
    }

    private ToolProviderSyncResult recordFailure(ToolProvider provider, Throwable failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        transactionTemplate.executeWithoutResult(status -> {
            ToolProviderPo providerPo = findOrCreateProvider(provider);
            providerPo.setLastSyncTime(LocalDateTime.now());
            providerPo.setLastError(message);
            providerMapper.updateById(providerPo);
        });
        log.warn("Tool provider {} synchronization failed", provider.getProviderId(), failure);
        return new ToolProviderSyncResult(provider.getProviderId(), false, 0, 0, 0, 0, 0,
                Instant.now(), message);
    }

    private ToolProviderPo findOrCreateProvider(ToolProvider provider) {
        Optional<ToolProviderPo> existing = providerMapper.selectByProviderId(provider.getProviderId());
        if (existing.isPresent()) {
            return existing.get();
        }
        ToolProviderPo providerPo = new ToolProviderPo()
                .setProviderId(provider.getProviderId())
                .setName(provider.getName())
                .setProviderType(provider.getProviderType().getValue())
                .setEndpoint(provider.getEndpoint().orElse(null))
                .setConfig(provider.getConfiguration())
                .setStatus(STATUS_ENABLED);
        providerMapper.insert(providerPo);
        return providerPo;
    }

    private void updateProviderMetadata(ToolProviderPo providerPo, ToolProvider provider) {
        providerPo.setName(provider.getName());
        providerPo.setProviderType(provider.getProviderType().getValue());
        providerPo.setEndpoint(provider.getEndpoint().orElse(null));
        providerPo.setConfig(provider.getConfiguration());
    }

    private ToolPo createTool(String providerId, ToolDefinition definition) {
        ToolReference reference = definition.reference();
        return new ToolPo()
                .setToolId(stableToolId(reference.namespace(), reference.name()))
                .setNamespace(reference.namespace())
                .setName(reference.name())
                .setProviderId(providerId)
                .setTitle(definition.title())
                .setDescription(definition.description())
                .setLifecycleState(ToolLifecycleStateEnum.DRAFT);
    }

    private ToolVersionPo createVersion(String toolId, ToolDefinition definition, String checksum) {
        return new ToolVersionPo()
                .setToolId(toolId)
                .setVersion(definition.reference().version())
                .setTitle(definition.title())
                .setDescription(definition.description())
                .setInputSchema(schemaMap(definition.inputSchema()))
                .setOutputSchema(schemaMap(definition.outputSchema()))
                .setCapabilities(toMap(definition.capabilities()))
                .setRiskProfile(toMap(definition.riskProfile()))
                .setDefaultConfiguration(definition.defaultConfiguration())
                .setDefaultPolicy(toMap(definition.defaultPolicy()))
                .setTags(definition.tags())
                .setChecksum(checksum)
                .setLifecycleState(ToolLifecycleStateEnum.DRAFT)
                .setRowVersion(0L);
    }

    private void updateVersion(ToolVersionPo versionPo, ToolDefinition definition, String checksum) {
        versionPo.setTitle(definition.title());
        versionPo.setDescription(definition.description());
        versionPo.setInputSchema(schemaMap(definition.inputSchema()));
        versionPo.setOutputSchema(schemaMap(definition.outputSchema()));
        versionPo.setCapabilities(toMap(definition.capabilities()));
        versionPo.setRiskProfile(toMap(definition.riskProfile()));
        versionPo.setDefaultConfiguration(definition.defaultConfiguration());
        versionPo.setDefaultPolicy(toMap(definition.defaultPolicy()));
        versionPo.setTags(definition.tags());
        versionPo.setChecksum(checksum);
    }

    private Map<String, Object> schemaMap(ToolSchema schema) {
        try {
            return objectMapper.readValue(schema.schema(), MAP_TYPE);
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid JSON schema", exception);
        }
    }

    private Map<String, Object> toMap(Object value) {
        return objectMapper.convertValue(value, MAP_TYPE);
    }

    private String checksum(ToolDefinition definition) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("reference", definition.reference());
        canonical.put("title", definition.title());
        canonical.put("description", definition.description());
        canonical.put("inputSchemaDialect", definition.inputSchema().dialect());
        canonical.put("inputSchema", schemaMap(definition.inputSchema()));
        canonical.put("outputSchemaDialect", definition.outputSchema().dialect());
        canonical.put("outputSchema", schemaMap(definition.outputSchema()));
        canonical.put("capabilities", Map.of(
                "supportsStreaming", definition.capabilities().supportsStreaming(),
                "executionModes", sortedNames(definition.capabilities().executionModes()),
                "supportsCancellation", definition.capabilities().supportsCancellation(),
                "supportsDryRun", definition.capabilities().supportsDryRun(),
                "inputModes", definition.capabilities().inputModes().stream().sorted().toList(),
                "outputModes", definition.capabilities().outputModes().stream().sorted().toList()));
        canonical.put("riskProfile", Map.of(
                "level", definition.riskProfile().level().name(),
                "readOnly", definition.riskProfile().readOnly(),
                "destructive", definition.riskProfile().destructive(),
                "reversible", definition.riskProfile().reversible(),
                "idempotent", definition.riskProfile().idempotent(),
                "openWorld", definition.riskProfile().openWorld(),
                "requiredScopes", definition.riskProfile().requiredScopes().stream().sorted().toList(),
                "allowedNetworkTargets", definition.riskProfile().allowedNetworkTargets().stream().sorted().toList()));
        canonical.put("defaultConfiguration", canonicalValue(definition.defaultConfiguration()));
        canonical.put("defaultPolicy", Map.of(
                "executionMode", definition.defaultPolicy().executionMode().name(),
                "timeoutMillis", definition.defaultPolicy().timeout().toMillis(),
                "maxRetries", definition.defaultPolicy().maxRetries(),
                "retryBackoffMillis", definition.defaultPolicy().retryBackoff().toMillis(),
                "maxOutputTokens", definition.defaultPolicy().maxOutputTokens(),
                "requiresApproval", definition.defaultPolicy().requiresApproval(),
                "allowsResultCache", definition.defaultPolicy().allowsResultCache()));
        canonical.put("tags", definition.tags().stream().sorted().toList());
        try {
            return sha256(objectMapper.writeValueAsBytes(canonical));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot calculate tool definition checksum", exception);
        }
    }

    private List<String> sortedNames(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().toList();
    }

    private Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) -> sorted.put(String.valueOf(key), canonicalValue(nested)));
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::canonicalValue).toList();
        }
        return value;
    }

    private String stableToolId(String namespace, String name) {
        return "tool_" + sha256((namespace + ":" + name).getBytes(StandardCharsets.UTF_8)).substring(0, 48);
    }

    private String sha256(byte[] input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private List<ToolReference> referencesOfProvider(String providerId) {
        List<ToolReference> references = new ArrayList<>();
        for (ToolPo tool : toolMapper.selectByProviderId(providerId)) {
            for (ToolVersionPo version : versionMapper.selectVersions(tool.getToolId())) {
                references.add(new ToolReference(tool.getNamespace(), tool.getName(), version.getVersion()));
            }
        }
        return references;
    }

    private void validateUniqueDefinitions(List<ToolDefinition> definitions) {
        Set<ToolReference> references = new HashSet<>();
        for (ToolDefinition definition : definitions) {
            if (!references.add(definition.reference())) {
                throw new IllegalStateException("provider returned duplicate tool definition: " + definition.reference());
            }
        }
    }

    private ToolProvider requireProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        ToolProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("unknown tool provider: " + providerId);
        }
        return provider;
    }

    private Map<String, ToolProvider> indexProviders(List<ToolProvider> providers) {
        Map<String, ToolProvider> result = new HashMap<>();
        for (ToolProvider provider : providers) {
            ToolProvider duplicate = result.putIfAbsent(provider.getProviderId(), provider);
            if (duplicate != null) {
                throw new IllegalStateException("duplicate tool provider id: " + provider.getProviderId());
            }
        }
        return Map.copyOf(result);
    }

    private String identityKey(ToolPo tool) {
        return identityKey(tool.getNamespace(), tool.getName());
    }

    private String identityKey(String namespace, String name) {
        return namespace + "\u0000" + name;
    }

    private void publishProviderEvent(String providerId, ToolClusterEventTypeEnum eventType) {
        ToolClusterEventPublisher publisher = eventPublisherProvider.getIfAvailable();
        if (publisher != null) {
            publisher.publishProviderChanged(providerId, eventType);
        }
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record PersistedRefresh(
            int createdCount,
            int updatedCount,
            int disabledCount,
            List<Tool<?, ?>> activated,
            List<ToolReference> unregistered
    ) {
    }
}
