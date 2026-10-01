package com.arte.ai.service.tool.workflow;

import com.arte.ai.api.tool.ToolBindingManager;
import com.arte.ai.api.tool.ToolGateway;
import com.arte.ai.api.tool.ToolRegistry;
import com.arte.ai.api.tool.ToolResult;
import com.arte.ai.api.tool.workflow.WorkflowCheckpointRepository;
import com.arte.ai.api.tool.workflow.WorkflowManager;
import com.arte.ai.api.tool.workflow.WorkflowNode;
import com.arte.ai.common.enums.tool.NeverToolCancellation;
import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;
import com.arte.ai.config.ToolClusterProperties;
import com.arte.ai.config.ToolExecutionProperties;
import com.arte.ai.mapper.tool.WorkflowNodeRunMapper;
import com.arte.ai.mapper.tool.WorkflowRunMapper;
import com.arte.ai.mapper.tool.WorkflowVersionMapper;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.pojo.tool.po.WorkflowNodeRunPo;
import com.arte.ai.pojo.tool.po.WorkflowRunPo;
import com.arte.ai.pojo.tool.po.WorkflowVersionPo;
import com.arte.ai.service.tool.cluster.ToolDistributedLockExecutor;
import com.arte.ai.service.tool.provider.SpringAiToolAdapter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import static org.junit.Assert.*;

public class DefaultWorkflowRunnerTest {
    private final WorkflowPersistenceCodec codec = new WorkflowPersistenceCodec(new ObjectMapper());
    private final ToolReference baseline = reference("1.0.0");
    private ToolReference chosen = reference("1.0.1");
    private final Map<String, WorkflowRunPo> runs = new HashMap<>();
    private final Map<String, WorkflowNodeRunPo> nodes = new HashMap<>();
    private final Map<String, WorkflowCheckpoint> checkpoints = new HashMap<>();
    private final List<ToolCallRequest> calls = new ArrayList<>();
    private WorkflowDefinition definition;
    private CompiledWorkflow compiled;
    private WorkflowVersionPo stored;
    private DefaultWorkflowRunner runner;
    private int bindingResolutions;
    private boolean bindingAvailable = true;
    private boolean suspendFirst;
    private boolean claiming = true;

    @Before
    public void setup() {
        var schema = new ToolSchema("https://json-schema.org/draft/2020-12/schema", "{\"type\":\"object\"}");
        definition = new WorkflowDefinition("workflow", "0.1.0", "Published workflow", null, schema, schema,
                List.of(new WorkflowNode.StartNode("start", "Start", Set.of(), Map.of()),
                        tool("first"), tool("second"),
                        new WorkflowNode.EndNode("end", "End", Map.of("result", "${second.result}"), Map.of())),
                List.of(edge("start", "first"), edge("first", "second"), edge("second", "end")), Set.of("stable"));
        compiled = new DefaultWorkflowCompiler(value -> new WorkflowValidationResult(List.of()), new ObjectMapper()).compile(definition);
        stored = new WorkflowVersionPo().setWorkflowId("workflow").setVersion("0.1.0")
                .setLifecycleState(ToolLifecycleStateEnum.PUBLISHED).setEntryNodeId("start")
                .setChecksum("legacy-jvm-order-checksum").setCompiledPlan(codec.encodePlan(compiled))
                .setPinnedTools(codec.encodePinnedTools(compiled.pinnedTools()));
        WorkflowVersionMapper versions = fake(WorkflowVersionMapper.class, (method, args) -> Optional.of(stored));
        WorkflowRunMapper runMapper = fake(WorkflowRunMapper.class, (method, args) -> switch (method) {
            case "insert" -> {
                WorkflowRunPo run = (WorkflowRunPo) args[0];
                run.setCreateTime(LocalDateTime.now());
                run.setUpdateTime(LocalDateTime.now());
                runs.put(run.getRunId(), run);
                yield 1;
            }
            case "selectByRunId" -> Optional.ofNullable(runs.get(args[0]));
            case "tryClaim" -> claiming ? 1 : 0;
            case "updateState", "renewLease" -> 1;
            case "selectRecoverable" ->
                    runs.values().stream().filter(run -> "waiting_tool".equals(run.getStatus())).toList();
            default -> throw new AssertionError(method);
        });
        WorkflowNodeRunMapper nodeMapper = fake(WorkflowNodeRunMapper.class, (method, args) -> switch (method) {
            case "insert" -> {
                WorkflowNodeRunPo node = (WorkflowNodeRunPo) args[0];
                nodes.put(node.getRunId() + ":" + node.getNodeId(), node);
                yield 1;
            }
            case "selectLatestAttempt" -> Optional.ofNullable(nodes.get(args[0] + ":" + args[1]));
            case "complete" -> {
                WorkflowNodeRunPo node = nodes.values().stream().filter(n -> n.getNodeRunId().equals(args[0])).findFirst().orElseThrow();
                node.setStatus((String) args[1]).setRowVersion(node.getRowVersion() + 1);
                yield 1;
            }
            default -> throw new AssertionError(method);
        });
        WorkflowManager manager = fake(WorkflowManager.class, (method, args) ->
                "owner".equals(args[0]) ? Optional.of(definition) : Optional.empty());
        ToolBindingManager bindings = fake(ToolBindingManager.class, (method, args) -> {
            if (!method.equals("resolveCompatible")) throw new AssertionError(method);
            bindingResolutions++;
            assertEquals("binding", args[2]);
            assertEquals(baseline, args[3]);
            return bindingAvailable ? Optional.of(new ResolvedToolBinding("binding", "owner", null, "local-java", "tool",
                    chosen, Map.of(), null, SpringAiToolAdapter.defaultExecutionPolicy(), true, 0)) : Optional.empty();
        });
        ToolGateway gateway = fake(ToolGateway.class, (method, args) -> switch (method) {
            case "invoke" -> {
                ToolCallRequest request = (ToolCallRequest) args[0];
                calls.add(request);
                if (suspendFirst && calls.size() == 1) {
                    var task = new ToolTaskHandle("task", request.callId(), request.tool(), ToolTaskHandle.Status.RUNNING,
                            null, null, null, Instant.now(), Instant.now(), 0, Map.of());
                    yield CompletableFuture.completedFuture(new ToolResult.Accepted<>(task, Map.of()));
                }
                yield CompletableFuture.completedFuture(success());
            }
            case "findTaskResult" -> CompletableFuture.completedFuture(Optional.of(success()));
            default -> throw new AssertionError(method);
        });
        ToolRegistry registry = fake(ToolRegistry.class, (method, args) -> Optional.empty());
        WorkflowCheckpointRepository repository = new WorkflowCheckpointRepository() {
            @Override
            public void save(WorkflowCheckpoint checkpoint) {
                checkpoints.put(checkpoint.runId(), checkpoint);
            }

            @Override
            public Optional<WorkflowCheckpoint> findLatest(String runId) {
                return Optional.ofNullable(checkpoints.get(runId));
            }
        };
        var factory = new DefaultListableBeanFactory();
        var locks = new ToolDistributedLockExecutor(factory.getBeanProvider(org.redisson.api.RedissonClient.class), new ToolClusterProperties());
        runner = new DefaultWorkflowRunner(runMapper, versions, nodeMapper, manager, codec, repository, gateway,
                registry, bindings, (s, input, name) -> {
        }, locks, new ToolExecutionProperties(),
                new ToolClusterIdentity("test"), new ObjectMapper(), Runnable::run);
    }

    @After
    public void close() {
        runner.close();
    }

    @Test
    public void oldPublishedWorkflowStartsWithoutRecompilingOrChangingItsVersion() {
        WorkflowRun run = runner.start("workflow", "0.1.0", context("owner")).toCompletableFuture().join();
        assertEquals(WorkflowRun.Status.SUCCEEDED, run.status());
        assertEquals("0.1.0", run.workflowVersion());
        assertEquals(List.of(reference("1.0.1"), reference("1.0.1")), calls.stream().map(ToolCallRequest::tool).toList());
        assertEquals(Map.of("result", "ok"), run.outputs());
        assertEquals("legacy-jvm-order-checksum", stored.getChecksum());
        assertEquals(baseline, codec.decodePinnedTools(stored.getPinnedTools()).get("first"));
        assertEquals(reference("1.0.1"), codec.decodePinnedTools((Map<String, Object>) checkpoints.get(run.runId()).state().get("resolvedTools")).get("first"));
    }

    @Test
    public void recompilingIdenticalLegacySourceDoesNotRequireRepublishing() {
        assertNotEquals(stored.getChecksum(), compiled.checksum());
        assertEquals(WorkflowRun.Status.SUCCEEDED, runner.start(compiled, context("owner")).toCompletableFuture().join().status());
    }

    @Test
    public void pinnedBindingKeepsOriginalToolForNewWorkflowRuns() {
        chosen = baseline;
        runner.start("workflow", "0.1.0", context("owner")).toCompletableFuture().join();
        assertEquals(List.of(baseline, baseline), calls.stream().map(ToolCallRequest::tool).toList());
    }

    @Test
    public void suspendedRunKeepsItsSelectedVersionsAfterAnotherUpgrade() {
        suspendFirst = true;
        WorkflowRun run = runner.start("workflow", "0.1.0", context("owner")).toCompletableFuture().join();
        assertEquals(WorkflowRun.Status.WAITING_TOOL, run.status());
        chosen = reference("1.0.2");
        runner.recoverRuns();
        assertEquals("succeeded", runs.get(run.runId()).getStatus());
        assertEquals(2, bindingResolutions);
        assertEquals(List.of(reference("1.0.1"), reference("1.0.1")), calls.stream().map(ToolCallRequest::tool).toList());
        WorkflowRun next = runner.start("workflow", "0.1.0", context("owner")).toCompletableFuture().join();
        assertEquals(WorkflowRun.Status.SUCCEEDED, next.status());
        assertEquals(reference("1.0.2"), calls.getLast().tool());
    }

    @Test
    public void legacyRunningCheckpointWithoutVersionsUsesOriginalPublishedVersions() {
        claiming = false;
        WorkflowRun run = runner.start("workflow", "0.1.0", context("owner")).toCompletableFuture().join();
        var checkpoint = checkpoints.get(run.runId());
        Map<String, Object> state = new HashMap<>(checkpoint.state());
        state.remove("resolvedTools");
        checkpoints.put(run.runId(), new WorkflowCheckpoint(checkpoint.checkpointId(), checkpoint.runId(), checkpoint.sequence(), state, checkpoint.createdAt()));
        runs.get(run.runId()).setStatus("waiting_tool");
        claiming = true;
        runner.recoverRuns();
        assertEquals(List.of(baseline, baseline), calls.stream().map(ToolCallRequest::tool).toList());
        assertEquals(2, bindingResolutions);
    }

    @Test
    public void refusesSourceChangesEvenWithForgedPublishedChecksum() {
        var changed = new WorkflowDefinition(definition.workflowId(), definition.version(), "Changed", null,
                definition.inputSchema(), definition.outputSchema(), definition.nodes(), definition.edges(), definition.tags());
        var forged = new CompiledWorkflow(changed, compiled.entryNodeId(), compiled.nodes(), compiled.edges(),
                compiled.pinnedTools(), stored.getChecksum());
        assertThrows(SecurityException.class, () -> runner.start(forged, context("owner")));
        assertTrue(runs.isEmpty());
        assertTrue(calls.isEmpty());
    }

    @Test
    public void refusesOtherOwnersDraftsAndUnavailableOrIncompatibleBindings() {
        assertThrows(SecurityException.class, () -> runner.start("workflow", "0.1.0", context("other")));
        stored.setLifecycleState(ToolLifecycleStateEnum.DRAFT);
        assertThrows(IllegalStateException.class, () -> runner.start("workflow", "0.1.0", context("owner")));
        stored.setLifecycleState(ToolLifecycleStateEnum.PUBLISHED);
        bindingAvailable = false;
        assertThrows(SecurityException.class, () -> runner.start("workflow", "0.1.0", context("owner")));
        assertTrue(runs.isEmpty());
        assertTrue(calls.isEmpty());
    }

    private WorkflowExecutionContext context(String owner) {
        return new WorkflowExecutionContext(new ToolExecutionContext("run", null, null, "trace", null,
                new ToolPrincipal(owner, owner, Set.of(), Set.of()), Instant.now().plusSeconds(300),
                NeverToolCancellation.INSTANCE, null, null, Map.of()), Map.of(), Map.of(), 20);
    }

    private WorkflowNode.ToolNode tool(String id) {
        return new WorkflowNode.ToolNode(id, id, baseline, Map.of(), Set.of("result"), Map.of("bindingId", "binding"), null);
    }

    private WorkflowEdge edge(String from, String to) {
        return new WorkflowEdge(from + to, from, null, to, null, null);
    }

    private static ToolReference reference(String version) {
        return new ToolReference("local", "query", version);
    }

    private ToolResult.Succeeded<DynamicToolResponse> success() {
        return new ToolResult.Succeeded<>(new DynamicToolResponse(Map.of("result", "ok"), "ok"), List.of(), List.of(), null, Map.of());
    }

    @SuppressWarnings("unchecked")
    private <T> T fake(Class<T> type, BiFunction<String, Object[], Object> action) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> action.apply(method.getName(), args));
    }
}
