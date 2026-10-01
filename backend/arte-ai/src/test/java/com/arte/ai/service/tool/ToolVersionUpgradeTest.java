package com.arte.ai.service.tool;

import com.arte.ai.api.tool.*;
import com.arte.ai.common.enums.tool.NeverToolCancellation;
import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;
import com.arte.ai.common.enums.tool.ToolProviderTypeEnum;
import com.arte.ai.common.enums.tool.ToolRiskLevelEnum;
import com.arte.ai.config.ToolClusterProperties;
import com.arte.ai.mapper.tool.*;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.pojo.tool.po.*;
import com.arte.ai.service.tool.cluster.ToolDistributedLockExecutor;
import com.arte.ai.service.tool.provider.SpringAiToolAdapter;
import org.junit.Before;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import static org.junit.Assert.*;

public class ToolVersionUpgradeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ToolVersionCompatibilityService compatibility = new ToolVersionCompatibilityService(json);
    private final DefaultToolPolicyMerger policies = new DefaultToolPolicyMerger();
    private final List<ToolVersionPo> versions = new ArrayList<>();
    private final Set<ToolReference> available = new HashSet<>();
    private final ToolPo catalog = new ToolPo().setToolId("tool-1").setNamespace("local").setName("query")
            .setProviderId("local-java").setLifecycleState(ToolLifecycleStateEnum.PUBLISHED).setLatestVersion("1.0.1");
    private final ToolBindingPo binding = new ToolBindingPo().setBindingId("stable-binding").setOwnerId("user")
            .setToolId("tool-1").setToolVersion("1.0.0").setVersionPolicy("follow-compatible")
            .setCredentialReference("user-credential").setConfiguration(Map.of("locale", "zh", "options", Map.of("limit", 5)))
            .setPolicyOverride(Map.of("timeoutMillis", 5000, "requiresApproval", true)).setEnabled(true).setRowVersion(3L);
    private DefaultToolRegistry registry;
    private ToolBindingVersionSelector selector;
    private DefaultToolBindingManager bindings;
    private ToolVersionMapper versionMapper;
    private ToolMapper toolMapper;
    private int writes;

    @Before
    public void setup() {
        versions.clear();
        available.clear();
        writes = 0;
        versions.add(version("1.0.0", null, ToolLifecycleStateEnum.PUBLISHED, 0));
        versions.add(version("1.0.1", "1.0.0", ToolLifecycleStateEnum.PUBLISHED, 1));
        versionMapper = mapper(ToolVersionMapper.class, (method, args) -> switch (method) {
            case "selectVersions" -> List.copyOf(versions);
            case "selectExact" -> versions.stream().filter(v -> v.getVersion().equals(args[1])).findFirst();
            case "declareCompatibility" -> {
                ToolVersionPo current = versions.stream().filter(v -> v.getId().equals(args[0])).findFirst().orElseThrow();
                if (current.getCompatibilityBaseVersion() != null || !Objects.equals(current.getRowVersion(), args[1]))
                    yield 0;
                current.setCompatibilityBaseVersion((String) args[2]).setReleaseNotes((String) args[3])
                        .setRowVersion(current.getRowVersion() + 1);
                yield 1;
            }
            default -> throw new AssertionError(method);
        });
        toolMapper = mapper(ToolMapper.class, (method, args) -> switch (method) {
            case "selectByIdentity", "selectByToolId" -> Optional.of(catalog);
            default -> throw new AssertionError(method);
        });
        registry = new DefaultToolRegistry(new DefaultToolDefinitionValidator(json), available::contains);
        selector = new ToolBindingVersionSelector(versionMapper, compatibility, available::contains, registry);
        ToolBindingMapper bindingMapper = mapper(ToolBindingMapper.class, (method, args) -> switch (method) {
            case "selectOwned" -> "user".equals(args[0]) ? Optional.of(binding) : Optional.empty();
            case "selectByScope" -> binding.getToolVersion().equals(args[3]) ? Optional.of(binding) : Optional.empty();
            case "selectByOwnerScope", "selectEnabledByScope" -> List.of(binding);
            case "updateWithVersion" -> {
                writes++;
                yield Objects.equals(args[1], 3L) ? 1 : 0;
            }
            default -> throw new AssertionError(method);
        });
        ToolProviderMapper providerMapper = mapper(ToolProviderMapper.class, (method, args) ->
                Optional.of(new ToolProviderPo().setProviderId("local-java").setStatus("enabled").setCredentialReference("provider-credential")));
        TransactionTemplate transaction = new TransactionTemplate() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(new org.springframework.transaction.support.SimpleTransactionStatus());
            }
        };
        var beanFactory = new DefaultListableBeanFactory();
        var locks = new ToolDistributedLockExecutor(beanFactory.getBeanProvider(org.redisson.api.RedissonClient.class),
                new ToolClusterProperties());
        bindings = new DefaultToolBindingManager(bindingMapper, toolMapper, versionMapper, providerMapper, policies,
                new DefaultToolConfigurationMerger(), available::contains, locks, transaction, selector);
        loadAll(Set.of("1.0.0"));
    }

    @Test
    public void compatibleUpgradePreservesAllUserConfigurationWithoutWritingBindings() {
        ResolvedToolBinding result = bindings.resolve("user", null, binding.getBindingId()).orElseThrow();
        assertEquals("1.0.1", result.tool().version());
        assertEquals("stable-binding", result.bindingId());
        assertEquals(binding.getConfiguration(), result.effectiveConfiguration());
        assertEquals("user-credential", result.credentialReference());
        assertEquals(5000, result.effectivePolicy().timeout().toMillis());
        assertTrue(result.effectivePolicy().requiresApproval());
        assertEquals("1.0.0", binding.getToolVersion());
        assertEquals(Long.valueOf(3), binding.getRowVersion());
        assertEquals(0, writes);
        ToolBindingView view = bindings.list("user", null).getFirst();
        assertEquals("1.0.0", view.baselineTool().version());
        assertEquals("auto-upgraded", view.updateStatus());
    }

    @Test
    public void assistantAssociationUsesUpgradedVersionWithoutReconfiguration() {
        var assistant = new com.arte.ai.pojo.assistant.AssistantDto();
        assistant.setId(7);
        assistant.setCreateBy("user");
        var assistantMapper = mapper(com.arte.ai.mapper.AssistantMapper.class, (method, args) -> assistant);
        var association = new AssistantToolPo().setAssistantId(7).setBindingId("stable-binding")
                .setEnabled(true).setSortOrder(2).setPolicyOverride(Map.of("maxOutputTokens", 1000));
        var associationMapper = mapper(AssistantToolMapper.class, (method, args) -> {
            if (!method.equals("selectEnabledByAssistantId"))
                throw new AssertionError("Unexpected association write: " + method);
            return List.of(association);
        });
        var manager = new DefaultAssistantToolManager(assistantMapper, associationMapper, bindings, policies,
                registry, null, null);
        var tool = manager.resolveForModel("user", null, 7).getFirst();
        assertEquals(ref("1.0.1"), tool.tool());
        assertEquals("stable-binding", tool.bindingId());
        assertEquals(binding.getConfiguration(), tool.effectiveConfiguration());
        assertEquals("user-credential", tool.credentialReference());
        assertEquals(1000, tool.effectivePolicy().maxOutputTokens());
        assertTrue(tool.effectivePolicy().requiresApproval());
        assertEquals(0, writes);
    }

    @Test
    public void workflowUsesCompatibleBindingAfterBaselineRuntimeIsRetired() {
        available.remove(ref("1.0.0"));
        registry.unregister(ref("1.0.0"));
        assertEquals(ref("1.0.1"), bindings.resolveCompatible("user", null, "stable-binding", ref("1.0.0")).orElseThrow().tool());
        var schema = new ToolSchema("https://json-schema.org/draft/2020-12/schema", "{\"type\":\"object\"}");
        var definition = new WorkflowDefinition("wf", "1", "Workflow", null, schema, schema, List.of(
                new com.arte.ai.api.tool.workflow.WorkflowNode.StartNode("start", "Start", Set.of(), Map.of()),
                new com.arte.ai.api.tool.workflow.WorkflowNode.ToolNode("query", "Query", ref("1.0.0"), Map.of(), Set.of("value"), Map.of("bindingId", "stable-binding"), null),
                new com.arte.ai.api.tool.workflow.WorkflowNode.EndNode("end", "End", Map.of(), Map.of())),
                List.of(new WorkflowEdge("a", "start", null, "query", null, null), new WorkflowEdge("b", "query", null, "end", null, null)), Set.of());
        var validator = new com.arte.ai.service.tool.workflow.DefaultWorkflowValidator(registry, bindings,
                new com.arte.ai.config.ToolExecutionProperties(), json);
        var compiler = new com.arte.ai.service.tool.workflow.DefaultWorkflowCompiler(validator, json);
        var result = compiler.compile(definition, new ToolPrincipal("user", "user", Set.of(), Set.of()));
        assertEquals(ref("1.0.0"), result.pinnedTools().get("query"));
        assertEquals(0, writes);
    }

    @Test
    public void workflowCannotFollowAnIncompatibleManuallySelectedBindingVersion() {
        binding.setToolVersion("1.0.1");
        versions.get(1).setCompatibilityBaseVersion(null);
        assertTrue(bindings.resolveCompatible("user", null, "stable-binding", ref("1.0.0")).isEmpty());
        binding.setVersionPolicy("pinned");
        versions.get(1).setCompatibilityBaseVersion("1.0.0");
        assertEquals(ref("1.0.1"), bindings.resolveCompatible("user", null, "stable-binding", ref("1.0.0")).orElseThrow().tool());
    }

    @Test
    public void pinnedBindingAndInFlightCallsRetainConcreteVersion() {
        assertEquals("1.0.0", bindings.resolveRequested("user", null, "stable-binding", ref("1.0.0")).orElseThrow().tool().version());
        binding.setVersionPolicy("pinned");
        assertEquals("1.0.0", bindings.resolve("user", null, "stable-binding").orElseThrow().tool().version());
        assertTrue(bindings.resolveRequested("user", null, "stable-binding", ref("1.0.1")).isEmpty());
        assertTrue(bindings.resolve("another-user", null, "stable-binding").isEmpty());
    }

    @Test
    public void draftAndBreakingReleasesDoNotChangeUsers() {
        versions.get(1).setLifecycleState(ToolLifecycleStateEnum.DRAFT);
        available.remove(ref("1.0.1"));
        assertEquals("1.0.0", selector.select(binding, catalog).orElseThrow().getVersion());
        versions.get(1).setLifecycleState(ToolLifecycleStateEnum.PUBLISHED).setCompatibilityBaseVersion(null);
        available.add(ref("1.0.1"));
        assertEquals("1.0.0", selector.select(binding, catalog).orElseThrow().getVersion());
        assertEquals("requires-review", bindings.list("user", null).getFirst().updateStatus());
    }

    @Test
    public void manualUpgradeUpdatesSameBindingWithOptimisticLock() {
        versions.get(1).setCompatibilityBaseVersion(null);
        ResolvedToolBinding result = bindings.save("user", new ToolBindingCommand("stable-binding", null,
                ref("1.0.1"), binding.getCredentialReference(), binding.getConfiguration(),
                policies.decodeOverride(binding.getPolicyOverride()), true, 3L, "pinned"));
        assertEquals("stable-binding", result.bindingId());
        assertEquals("1.0.1", result.tool().version());
        assertEquals(binding.getConfiguration(), result.effectiveConfiguration());
        assertEquals("user-credential", result.credentialReference());
        assertEquals(4, result.rowVersion());
        assertEquals(1, writes);
        assertThrows(IllegalStateException.class, () -> bindings.save("user", new ToolBindingCommand("stable-binding", null,
                ref("1.0.1"), binding.getCredentialReference(), binding.getConfiguration(), null, true, 2L, "pinned")));
    }

    @Test
    public void followsTransitiveExplicitChainAndFallsBackFromDisabledRelease() {
        ToolVersionPo third = version("1.0.2", "1.0.1", ToolLifecycleStateEnum.PUBLISHED, 2);
        versions.add(third);
        available.add(ref("1.0.2"));
        registry.register("local-java", adapter("1.0.2", Set.of()));
        assertEquals("1.0.2", selector.select(binding, catalog).orElseThrow().getVersion());
        third.setLifecycleState(ToolLifecycleStateEnum.DISABLED);
        available.remove(ref("1.0.2"));
        assertEquals("1.0.1", selector.select(binding, catalog).orElseThrow().getVersion());
        versions.get(1).setCompatibilityBaseVersion("1.0.2");
        assertFalse(compatibility.follows("1.0.0", third, versions));
    }

    @Test
    public void refusesSchemaDefaultPolicyAndPrivilegeChanges() {
        var base = versions.get(0);
        var next = versions.get(1);
        next.setInputSchema(Map.of("type", "object", "required", List.of("extra")));
        assertTrue(compatibility.problems(base, next).contains("输入参数契约发生变化"));
        next.setInputSchema(base.getInputSchema()).setDefaultConfiguration(Map.of("locale", "en"));
        assertTrue(compatibility.problems(base, next).contains("默认配置发生变化"));
        next.setDefaultConfiguration(base.getDefaultConfiguration());
        var risk = new HashMap<>(base.getRiskProfile());
        risk.put("requiredScopes", List.of("write"));
        next.setRiskProfile(risk);
        assertThrows(IllegalArgumentException.class, () -> compatibility.requireCompatible(base, next));
        risk.put("requiredScopes", List.of());
        risk.put("level", "critical");
        assertTrue(compatibility.problems(base, next).contains("风险级别提高"));
        next.setRiskProfile(base.getRiskProfile());
        var policy = new HashMap<>(base.getDefaultPolicy());
        policy.put("requiresApproval", true);
        next.setDefaultPolicy(policy);
        assertTrue(compatibility.problems(base, next).contains("新增人工审批要求"));
    }

    @Test
    public void acceptsLowerRiskAndIgnoresCapabilitySetOrdering() {
        var base = versions.get(0);
        var next = versions.get(1);
        var risk = new HashMap<>(base.getRiskProfile());
        risk.put("level", "medium");
        risk.put("readOnly", false);
        base.setRiskProfile(risk);
        next.setCapabilities(new HashMap<>(next.getCapabilities()));
        next.getCapabilities().put("executionModes", List.of("non-blocking", "blocking"));
        base.getCapabilities().put("executionModes", List.of("blocking", "non-blocking"));
        assertTrue(compatibility.problems(base, next).isEmpty());
    }

    @Test
    public void explicitDraftExecutorKeepsHistoricRiskAndExecutionAvailable() {
        var risk = new HashMap<>(versions.get(0).getRiskProfile());
        risk.put("level", "medium");
        risk.put("readOnly", false);
        versions.get(0).setRiskProfile(risk);
        versions.get(1).setLifecycleState(ToolLifecycleStateEnum.DRAFT);
        available.remove(ref("1.0.1"));
        List<Tool<?, ?>> runtime = loader().load(provider(adapter("1.0.1", Set.of("1.0.0"))));
        assertEquals(1, runtime.size());
        var old = runtime.getFirst();
        assertEquals(ref("1.0.0"), old.getDefinition().reference());
        assertEquals(ToolRiskLevelEnum.MEDIUM, old.getDefinition().riskProfile().level());
        assertFalse(old.getDefinition().riskProfile().readOnly());
        @SuppressWarnings("unchecked") var executable = (Tool<DynamicToolRequest, DynamicToolResponse>) old;
        var context = new ToolExecutionContext("run", null, null, "trace", null,
                new ToolPrincipal("user", "user", Set.of(), Set.of()), Instant.now().plusSeconds(30),
                NeverToolCancellation.INSTANCE, null, null, Map.of());
        assertTrue(executable.execute(new ToolInvocation<>("call", ref("1.0.0"), new DynamicToolRequest(Map.of()), context,
                old.getDefinition().defaultPolicy())).toCompletableFuture().join() instanceof ToolResult.Succeeded<?>);
    }

    @Test
    public void refusesUndeclaredOrIncompatibleDraftAliasAndKeepsNativeOldImplementation() {
        versions.get(1).setLifecycleState(ToolLifecycleStateEnum.DRAFT);
        available.remove(ref("1.0.1"));
        assertTrue(loader().load(provider(adapter("1.0.1", Set.of()))).isEmpty());
        versions.get(1).setInputSchema(Map.of("type", "object", "required", List.of("new-field")));
        assertThrows(IllegalStateException.class, () -> loader().load(provider(adapter("1.0.1", Set.of("1.0.0")))));
        versions.get(1).setInputSchema(versions.get(0).getInputSchema());
        var nativeOld = adapter("1.0.0", Set.of());
        assertSame(nativeOld, loader().load(provider(adapter("1.0.1", Set.of("1.0.0")), nativeOld)).getFirst());
    }

    @Test
    public void canConfirmCompatibilityOnceForAlreadyPublishedReleaseWithoutChangingSnapshot() {
        ToolVersionPo released = versions.get(1).setCompatibilityBaseVersion(null);
        LocalDateTime originalTime = released.getPublishedAt();
        var originalRisk = new HashMap<>(released.getRiskProfile());
        var originalSchema = released.getInputSchema();
        DefaultToolLifecycleManager lifecycle = lifecycle();
        lifecycle.publish(ref("1.0.1"), new ToolPublishCommand("1.0.0", "只读低风险，保留配置"));
        assertEquals("1.0.0", released.getCompatibilityBaseVersion());
        assertEquals("只读低风险，保留配置", released.getReleaseNotes());
        assertEquals(originalTime, released.getPublishedAt());
        assertEquals(originalRisk, released.getRiskProfile());
        assertEquals(originalSchema, released.getInputSchema());
        assertEquals("1.0.1", bindings.resolve("user", null, "stable-binding").orElseThrow().tool().version());
        assertEquals(0, writes);
        lifecycle.publish(ref("1.0.1"), new ToolPublishCommand("1.0.0", "只读低风险，保留配置"));
        assertEquals(Long.valueOf(1), released.getRowVersion());
        assertThrows(IllegalStateException.class, () -> lifecycle.publish(ref("1.0.1"),
                new ToolPublishCommand("1.0.0", "重写升级计划")));
    }

    @Test
    public void refusesRetroactiveReverseCompatibilityAndMissingReleaseNotes() {
        assertThrows(IllegalArgumentException.class, () -> compatibility.requireReleaseBaseline(versions.get(1), versions.get(0)));
        versions.get(1).setCompatibilityBaseVersion(null);
        assertThrows(IllegalArgumentException.class, () -> lifecycle().publish(ref("1.0.1"), new ToolPublishCommand("1.0.0", null)));
        assertNull(versions.get(1).getCompatibilityBaseVersion());
    }

    private DefaultToolLifecycleManager lifecycle() {
        var provider = provider(adapter("1.0.1", Set.of("1.0.0")));
        ToolProviderManager providers = mapper(ToolProviderManager.class, (method, args) -> {
            if (!Set.of("synchronize", "reconcileLocal").contains(method)) throw new AssertionError(method);
            return CompletableFuture.completedFuture(new ToolProviderSyncResult("local-java", true, 1, 0, 0, 2, 0, Instant.now(), null));
        });
        var beanFactory = new DefaultListableBeanFactory();
        var locks = new ToolDistributedLockExecutor(beanFactory.getBeanProvider(org.redisson.api.RedissonClient.class), new ToolClusterProperties());
        TransactionTemplate transaction = new TransactionTemplate() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(new org.springframework.transaction.support.SimpleTransactionStatus());
            }
        };
        return new DefaultToolLifecycleManager(List.of(provider), toolMapper, versionMapper,
                new DefaultToolDefinitionValidator(json), registry, transaction,
                beanFactory.getBeanProvider(ToolClusterEventPublisher.class), locks, providers, compatibility);
    }

    private void loadAll(Set<String> compatible) {
        available.add(ref("1.0.0"));
        available.add(ref("1.0.1"));
        registry.replaceProvider("local-java", loader().load(provider(adapter("1.0.1", compatible))));
    }

    private ToolRuntimeVersionLoader loader() {
        return new ToolRuntimeVersionLoader(toolMapper, versionMapper, available::contains, compatibility, policies, json);
    }

    private ToolReference ref(String version) {
        return new ToolReference("local", "query", version);
    }

    private SpringAiToolAdapter adapter(String version, Set<String> compatible) {
        ToolCallback callback = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return new org.springframework.ai.tool.definition.DefaultToolDefinition("query", "Query a record", "{\"type\":\"object\",\"properties\":{}}");
            }

            @Override
            public String call(String input) {
                return "{\"ok\":true}";
            }
        };
        return new SpringAiToolAdapter("local", version, callback,
                new ToolRiskProfile(ToolRiskLevelEnum.LOW, true, false, false, false, false, Set.of(), Set.of()),
                SpringAiToolAdapter.defaultExecutionPolicy(), json, Runnable::run, compatible);
    }

    @SuppressWarnings("unchecked")
    private ToolVersionPo version(String version, String base, ToolLifecycleStateEnum state, int order) {
        ToolDefinition definition = adapter(version, Set.of()).getDefinition();
        var result = new ToolVersionPo().setToolId("tool-1").setVersion(version).setCompatibilityBaseVersion(base)
                .setPublishedAt(LocalDateTime.of(2026, 10, 1, 1, order)).setLifecycleState(state).setRowVersion(0L)
                .setTitle(definition.title()).setDescription(definition.description())
                .setInputSchema(json.readValue(definition.inputSchema().schema(), Map.class))
                .setOutputSchema(json.readValue(definition.outputSchema().schema(), Map.class))
                .setCapabilities(new HashMap<>(json.convertValue(definition.capabilities(), Map.class)))
                .setRiskProfile(new HashMap<>(json.convertValue(definition.riskProfile(), Map.class)))
                .setDefaultConfiguration(Map.of()).setTags(Set.of("spring-ai"))
                .setDefaultPolicy(Map.of("executionMode", "blocking", "timeoutMillis", 30000,
                        "maxRetries", 0, "retryBackoffMillis", 0, "maxOutputTokens", 4096,
                        "requiresApproval", false, "allowsResultCache", false));
        result.setId((long) order + 1);
        return result;
    }

    private ToolProvider provider(Tool<?, ?>... tools) {
        return new ToolProvider() {
            @Override
            public String getProviderId() {
                return "local-java";
            }

            @Override
            public ToolProviderTypeEnum getProviderType() {
                return ToolProviderTypeEnum.LOCAL;
            }

            @Override
            public List<ToolDefinition> listDefinitions() {
                return Arrays.stream(tools).map(Tool::getDefinition).toList();
            }

            @Override
            public Optional<Tool<?, ?>> resolve(ToolReference reference) {
                return Arrays.stream(tools).filter(t -> t.getDefinition().reference().equals(reference)).findFirst();
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> refresh() {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private <T> T mapper(Class<T> type, BiFunction<String, Object[], Object> handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> handler.apply(method.getName(), args));
    }
}
