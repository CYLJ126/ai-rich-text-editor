package com.arte.ai.service.tool.workflow;

import com.arte.ai.api.tool.*;
import com.arte.ai.api.tool.workflow.WorkflowCheckpointRepository;
import com.arte.ai.api.tool.workflow.WorkflowManager;
import com.arte.ai.api.tool.workflow.WorkflowNode;
import com.arte.ai.api.tool.workflow.WorkflowRunner;
import com.arte.ai.common.enums.tool.NeverToolCancellation;
import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;
import com.arte.ai.common.enums.tool.WorkflowNodeTypeEnum;
import com.arte.ai.config.ToolExecutionProperties;
import com.arte.ai.mapper.tool.WorkflowNodeRunMapper;
import com.arte.ai.mapper.tool.WorkflowRunMapper;
import com.arte.ai.mapper.tool.WorkflowVersionMapper;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.pojo.tool.po.WorkflowNodeRunPo;
import com.arte.ai.pojo.tool.po.WorkflowRunPo;
import com.arte.ai.pojo.tool.po.WorkflowVersionPo;
import com.arte.ai.service.tool.DefaultToolGateway;
import com.arte.ai.service.tool.cluster.ToolDistributedLockExecutor;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

/**
 * 基于检查点的 DAG 工作流运行器。
 *
 * <p>节点按依赖就绪并行执行；TOOL 节点只调用 ToolGateway。运行权由数据库租约和
 * Redisson 运行锁共同保护，重启后定时扫描可恢复运行中及等待工具/审批的实例。</p>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Service
public class DefaultWorkflowRunner implements WorkflowRunner {

    private static final String STATE_COMPLETED = "completedNodes";
    private static final String STATE_ENABLED = "enabledNodes";
    private static final String STATE_VARIABLES = "variables";
    private static final String STATE_PENDING = "pendingTasks";
    private static final String STATE_ROLES = "principalRoles";
    private static final String STATE_SCOPES = "principalScopes";
    private static final String STATE_ATTRIBUTES = "toolAttributes";
    private static final String STATE_RESOLVED_TOOLS = "resolvedTools";

    private final WorkflowRunMapper runMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final WorkflowManager workflowManager;
    private final WorkflowPersistenceCodec codec;
    private final WorkflowCheckpointRepository checkpoints;
    private final ToolGateway toolGateway;
    private final ToolRegistry toolRegistry;
    private final ToolBindingManager bindingManager;
    private final ToolSchemaValidator schemaValidator;
    private final ToolDistributedLockExecutor lockExecutor;
    private final ToolExecutionProperties properties;
    private final ToolClusterIdentity clusterIdentity;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final ScheduledExecutorService leaseScheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("workflow-lease-", 0).factory());

    public DefaultWorkflowRunner(WorkflowRunMapper runMapper, WorkflowVersionMapper versionMapper,
                                 WorkflowNodeRunMapper nodeRunMapper, WorkflowManager workflowManager,
                                 WorkflowPersistenceCodec codec, WorkflowCheckpointRepository checkpoints,
                                 ToolGateway toolGateway, ToolRegistry toolRegistry, ToolBindingManager bindingManager,
                                 ToolSchemaValidator schemaValidator,
                                 ToolDistributedLockExecutor lockExecutor, ToolExecutionProperties properties,
                                 ToolClusterIdentity clusterIdentity, ObjectMapper objectMapper,
                                 @Qualifier("toolCallbackExecutor") Executor executor) {
        this.runMapper = runMapper;
        this.versionMapper = versionMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.workflowManager = workflowManager;
        this.codec = codec;
        this.checkpoints = checkpoints;
        this.toolGateway = toolGateway;
        this.toolRegistry = toolRegistry;
        this.bindingManager = bindingManager;
        this.schemaValidator = schemaValidator;
        this.lockExecutor = lockExecutor;
        this.properties = properties;
        this.clusterIdentity = clusterIdentity;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @Override
    public CompletionStage<WorkflowRun> start(CompiledWorkflow workflow, WorkflowExecutionContext context) {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(context, "context");
        WorkflowVersionPo version = versionMapper.selectExact(workflow.source().workflowId(),
                workflow.source().version()).orElseThrow(() ->
                new IllegalArgumentException("workflow version is not persisted"));
        if (version.getLifecycleState() != ToolLifecycleStateEnum.PUBLISHED) {
            throw new IllegalStateException("only published workflow versions can run");
        }
        CompiledWorkflow executionPlan = publishedPlan(version, context);
        // 旧校验和受 Map/Set 的 JVM 随机迭代顺序影响。比较规范化定义，执行仍只读取已发布快照。
        if (!codec.checksum(executionPlan.source()).equals(codec.checksum(workflow.source()))) {
            throw new SecurityException("workflow plan does not match the published version");
        }
        return startPublished(executionPlan, context);
    }

    @Override
    public CompletionStage<WorkflowRun> start(String workflowId, String version, WorkflowExecutionContext context) {
        Objects.requireNonNull(context, "context");
        WorkflowVersionPo stored = versionMapper.selectExact(workflowId, version)
                .orElseThrow(() -> new IllegalArgumentException("workflow version is not persisted"));
        return startPublished(publishedPlan(stored, context), context);
    }

    private CompiledWorkflow publishedPlan(WorkflowVersionPo version, WorkflowExecutionContext context) {
        if (version.getLifecycleState() != ToolLifecycleStateEnum.PUBLISHED) {
            throw new IllegalStateException("only published workflow versions can run");
        }
        WorkflowDefinition persisted = workflowManager.find(context.toolContext().principal().ownerId(),
                        version.getWorkflowId(), version.getVersion())
                .orElseThrow(() -> new SecurityException("workflow version is unavailable"));
        return codec.decodeCompiled(persisted, version);
    }

    private CompletionStage<WorkflowRun> startPublished(CompiledWorkflow executionPlan, WorkflowExecutionContext context) {
        schemaValidator.validate(executionPlan.source().inputSchema(), context.inputs(), "workflow inputs");
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        int maximumSteps = Math.min(context.maximumSteps(),
                executionPlan.source().executionPolicy().maximumSteps());
        WorkflowRunPo run = new WorkflowRunPo().setRunId(runId)
                .setWorkflowId(executionPlan.source().workflowId())
                .setWorkflowVersion(executionPlan.source().version())
                .setOwnerId(context.toolContext().principal().ownerId())
                .setSubjectId(context.toolContext().principal().subjectId())
                .setTraceId(context.toolContext().traceId()).setStatus("running")
                .setInputs(context.inputs()).setVariables(context.variables())
                .setActiveNodeIds(Set.of(executionPlan.entryNodeId())).setOutputs(Map.of())
                .setMaximumSteps(maximumSteps).setCurrentSteps(0)
                .setStartedAt(toLocal(now))
                .setDeadlineAt(toLocal(now.plus(executionPlan.source().executionPolicy().timeout())))
                .setRowVersion(0L);
        run.setCreateBy(run.getOwnerId());
        run.setUpdateBy(run.getOwnerId());
        Map<String, ToolReference> resolvedTools = resolveTools(executionPlan, context);
        runMapper.insert(run);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put(STATE_COMPLETED, List.of());
        state.put(STATE_ENABLED, List.of(executionPlan.entryNodeId()));
        state.put(STATE_VARIABLES, context.variables());
        state.put(STATE_PENDING, Map.of());
        state.put(STATE_ROLES, context.toolContext().principal().roles());
        state.put(STATE_SCOPES, context.toolContext().principal().scopes());
        state.put(STATE_ATTRIBUTES, context.toolContext().attributes());
        state.put(STATE_RESOLVED_TOOLS, codec.encodePinnedTools(resolvedTools));
        saveCheckpoint(runId, 0, state);
        return CompletableFuture.supplyAsync(() -> executeRun(runId), executor);
    }

    private Map<String, ToolReference> resolveTools(CompiledWorkflow plan, WorkflowExecutionContext context) {
        Map<String, ToolReference> result = new LinkedHashMap<>(plan.pinnedTools());
        for (WorkflowNode node : plan.nodes().values()) {
            if (!(node instanceof WorkflowNode.ToolNode toolNode)) continue;
            String bindingId = configurationText(node.configuration().get("bindingId"));
            if (bindingId == null) {
                if (properties.isBindingRequired())
                    throw new SecurityException("workflow tool binding is required: " + node.nodeId());
                continue;
            }
            ResolvedToolBinding binding = bindingManager.resolveCompatible(context.toolContext().principal().ownerId(),
                            configurationText(node.configuration().get("workspaceId")), bindingId, toolNode.tool())
                    .orElseThrow(() -> new SecurityException("workflow binding has no available compatible tool version: " + node.nodeId()));
            result.put(node.nodeId(), binding.tool());
        }
        return Map.copyOf(result);
    }

    private CompiledWorkflow withTools(CompiledWorkflow plan, Map<String, ToolReference> tools) {
        if (!tools.keySet().equals(plan.pinnedTools().keySet())) {
            throw new IllegalStateException("workflow checkpoint tool versions are incomplete");
        }
        return new CompiledWorkflow(plan.source(), plan.entryNodeId(), plan.nodes(), plan.edges(), tools,
                plan.dependencies(), plan.executionOrder(), plan.parallelGroups(), plan.variableMappings(), plan.checksum());
    }

    private String configurationText(Object value) {
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    @Override
    public CompletionStage<WorkflowRun> resume(String resumeToken) {
        return resume(resumeToken, null);
    }

    @Override
    public CompletionStage<WorkflowRun> resume(String resumeToken, String ownerId) {
        String hash = hash(requireText(resumeToken, "resumeToken"));
        WorkflowRunPo run = runMapper.selectByResumeTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("invalid workflow resume token"));
        if (ownerId != null && !ownerId.equals(run.getOwnerId())) {
            throw new SecurityException("workflow run is unavailable");
        }
        if (!Set.of("waiting_tool", "waiting_approval", "paused").contains(run.getStatus())) {
            throw new IllegalStateException("workflow is not suspended");
        }
        Map<String, String> pending = pending(latestState(run.getRunId()));
        if ("waiting_approval".equals(run.getStatus())) {
            toolGateway.resume(resumeToken, run.getOwnerId()).toCompletableFuture().join();
        }
        release(run, "running", null);
        return CompletableFuture.supplyAsync(() -> executeRun(run.getRunId()), executor);
    }

    @Override
    public CompletionStage<WorkflowRun> cancel(String runId) {
        return CompletableFuture.supplyAsync(() -> {
            WorkflowRunPo run = runMapper.selectByRunId(runId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown workflow run"));
            if (terminal(run.getStatus())) return toDomain(run, null);
            Map<String, String> pending = pending(latestState(runId));
            pending.values().forEach(taskId -> toolGateway.findTask(taskId, run.getOwnerId())
                    .toCompletableFuture().join().ifPresent(task ->
                            toolGateway.cancel(task.callId(), run.getOwnerId()).toCompletableFuture().join()));
            run.setStatus("cancelled").setCompletedAt(LocalDateTime.now())
                    .setErrorInfo(error("WORKFLOW_CANCELLED", "CANCELLED", null,
                            "workflow cancelled by user")).setActiveNodeIds(Set.of())
                    .setWorkerId(null).setLeaseUntil(null);
            update(run);
            return toDomain(run, null);
        }, executor);
    }

    @Override
    public CompletionStage<WorkflowRun> cancel(String runId, String ownerId) {
        WorkflowRunPo run = runMapper.selectByRunId(runId)
                .filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new SecurityException("workflow run is unavailable"));
        return cancel(run.getRunId());
    }

    @Override
    public CompletionStage<Optional<WorkflowRun>> findRun(String runId, String ownerId) {
        return CompletableFuture.completedFuture(runMapper.selectByRunId(runId)
                .filter(value -> value.getOwnerId().equals(ownerId)).map(value -> toDomain(value, null)));
    }

    @Scheduled(initialDelayString = "${arte.ai.tool.execution.workflow-recovery-initial-delay:10s}",
            fixedDelayString = "${arte.ai.tool.execution.workflow-recovery-interval:10s}")
    public void recoverRuns() {
        runMapper.selectRecoverable(LocalDateTime.now(), properties.getWorkflowRecoveryBatchSize())
                .forEach(run -> CompletableFuture.runAsync(() -> executeRun(run.getRunId()), executor));
    }

    private WorkflowRun executeRun(String runId) {
        return lockExecutor.execute("workflow-run:" + runId, () -> executeLocked(runId));
    }

    private WorkflowRun executeLocked(String runId) {
        if (runMapper.tryClaim(runId, clusterIdentity.instanceId(),
                LocalDateTime.now().plus(properties.getWorkflowLease()), LocalDateTime.now()) != 1) {
            return toDomain(runMapper.selectByRunId(runId).orElseThrow(), null);
        }
        WorkflowRunPo run = runMapper.selectByRunId(runId).orElseThrow();
        ScheduledFuture<?> renewal = leaseScheduler.scheduleAtFixedRate(
                () -> runMapper.renewLease(runId, clusterIdentity.instanceId(),
                        LocalDateTime.now().plus(properties.getWorkflowLease())),
                properties.getWorkflowLeaseRenewInterval().toMillis(),
                properties.getWorkflowLeaseRenewInterval().toMillis(), TimeUnit.MILLISECONDS);
        try {
            WorkflowDefinition definition = workflowManager.find(run.getOwnerId(), run.getWorkflowId(),
                    run.getWorkflowVersion()).orElseThrow();
            WorkflowVersionPo version = versionMapper.selectExact(run.getWorkflowId(), run.getWorkflowVersion())
                    .orElseThrow(() -> new IllegalStateException("workflow version is missing"));
            CompiledWorkflow plan = codec.decodeCompiled(definition, version);
            Map<String, Object> state = new LinkedHashMap<>(latestState(runId));
            // 老运行没有此字段时继续使用原发布版本，不在恢复时重新解析绑定。
            if (state.containsKey(STATE_RESOLVED_TOOLS)) {
                plan = withTools(plan, codec.decodePinnedTools(objectMap(state.get(STATE_RESOLVED_TOOLS))));
            }
            return drive(run, plan, state);
        } catch (Exception exception) {
            failRun(run, "WORKFLOW_EXECUTION_FAILED", null, rootMessage(exception));
            return toDomain(run, null);
        } finally {
            renewal.cancel(false);
        }
    }

    private WorkflowRun drive(WorkflowRunPo run, CompiledWorkflow plan, Map<String, Object> state) {
        Set<String> completed = stringSet(state.get(STATE_COMPLETED));
        Set<String> enabled = stringSet(state.get(STATE_ENABLED));
        Map<String, Object> variables = objectMap(state.get(STATE_VARIABLES));
        Map<String, String> pending = pending(state);

        if (!pending.isEmpty()) {
            PendingOutcome outcome = consumePending(run, plan, variables, completed, pending);
            if (!outcome.ready()) {
                releaseLease(run);
                return toDomain(run, null);
            }
            if (outcome.error() != null) {
                failRun(run, "WORKFLOW_TOOL_FAILED", outcome.nodeId(), outcome.error());
                return toDomain(run, null);
            }
            pending.clear();
            outcome.nodeIds().forEach(nodeId -> enableOutgoing(plan, nodeId, variables, enabled));
            state.put(STATE_PENDING, Map.of());
        }

        run.setStatus("running").setResumeTokenHash(null);
        while (true) {
            if (run.getDeadlineAt() != null && !run.getDeadlineAt().isAfter(LocalDateTime.now())) {
                timeoutRun(run, "workflow execution budget expired");
                return toDomain(run, null);
            }
            if (run.getCurrentSteps() >= run.getMaximumSteps()) {
                failRun(run, "WORKFLOW_MAX_STEPS", null, "maximum workflow steps exceeded");
                return toDomain(run, null);
            }
            List<String> ready = enabled.stream().filter(id -> !completed.contains(id))
                    .filter(id -> dependenciesReady(id, plan, enabled, completed)).sorted().toList();
            if (ready.isEmpty()) {
                boolean ended = completed.stream().map(plan.nodes()::get).filter(Objects::nonNull)
                        .anyMatch(node -> node.type() == WorkflowNodeTypeEnum.END);
                if (ended) {
                    Map<String, Object> outputs = workflowOutputs(plan, variables, completed);
                    schemaValidator.validate(plan.source().outputSchema(), outputs, "workflow outputs");
                    run.setStatus("succeeded").setOutputs(outputs).setActiveNodeIds(Set.of())
                            .setCompletedAt(LocalDateTime.now()).setWorkerId(null).setLeaseUntil(null);
                    update(run);
                    checkpoint(run, state, completed, enabled, variables, pending);
                    return toDomain(run, null);
                }
                failRun(run, "WORKFLOW_DEADLOCK", null, "no executable node remains");
                return toDomain(run, null);
            }
            int limit = Math.min(plan.source().executionPolicy().maximumParallelism(), ready.size());
            List<String> batch = ready.subList(0, limit);
            run.setActiveNodeIds(Set.copyOf(batch));
            update(run);
            List<CompletableFuture<NodeOutcome>> futures = batch.stream()
                    .map(nodeId -> CompletableFuture.supplyAsync(() -> executeNodeWithRetry(run, plan,
                            plan.nodes().get(nodeId), variables), executor)).toList();
            List<NodeOutcome> outcomes = futures.stream().map(CompletableFuture::join).toList();
            String waitingToken = null;
            boolean waitingApproval = false;
            for (NodeOutcome outcome : outcomes) {
                run.setCurrentSteps(run.getCurrentSteps() + 1);
                if (outcome.error() != null) {
                    failRun(run, "WORKFLOW_NODE_FAILED", outcome.nodeId(), outcome.error());
                    return toDomain(run, null);
                }
                if (outcome.taskId() != null) {
                    pending.put(outcome.nodeId(), outcome.taskId());
                    if (outcome.resumeToken() != null) waitingToken = outcome.resumeToken();
                    waitingApproval = waitingApproval || outcome.requiresApproval();
                    continue;
                }
                storeOutputs(outcome.nodeId(), outcome.outputs(), variables);
                completed.add(outcome.nodeId());
                enableOutgoing(plan, outcome.nodeId(), variables, enabled);
            }
            if (!pending.isEmpty()) {
                state.put(STATE_PENDING, Map.copyOf(pending));
                String rawToken = waitingToken == null
                        ? UUID.randomUUID().toString() + UUID.randomUUID() : waitingToken;
                run.setStatus(waitingApproval ? "waiting_approval" : "waiting_tool")
                        .setResumeTokenHash(hash(rawToken)).setActiveNodeIds(Set.copyOf(pending.keySet()))
                        .setWorkerId(null).setLeaseUntil(null);
                update(run);
                checkpoint(run, state, completed, enabled, variables, pending);
                return toDomain(run, rawToken);
            }
            checkpoint(run, state, completed, enabled, variables, pending);
        }
    }

    private NodeOutcome executeNode(WorkflowRunPo run, CompiledWorkflow plan, WorkflowNode node,
                                    Map<String, Object> variables) {
        Map<String, Object> inputs = resolveInputs(node,
                plan.variableMappings().getOrDefault(node.nodeId(), node.inputBindings()),
                run.getInputs(), variables);
        int attempt = nodeRunMapper.selectLatestAttempt(run.getRunId(), node.nodeId())
                .map(value -> value.getAttempt() + 1).orElse(1);
        String nodeRunId = UUID.randomUUID().toString();
        Instant started = Instant.now();
        WorkflowNodeRunPo nodeRun = new WorkflowNodeRunPo().setNodeRunId(nodeRunId)
                .setRunId(run.getRunId()).setNodeId(node.nodeId()).setNodeType(node.type())
                .setAttempt(attempt).setStatus("running").setInputs(inputs).setRowVersion(0L)
                .setStartedAt(toLocal(started));
        nodeRun.setCreateBy(run.getOwnerId());
        nodeRun.setUpdateBy(run.getOwnerId());
        nodeRunMapper.insert(nodeRun);
        try {
            NodeOutcome outcome = node instanceof WorkflowNode.ToolNode toolNode
                    ? executeToolNode(run, plan, toolNode, inputs) : executeStructuralNode(node, inputs);
            String state = outcome.taskId() == null ? "succeeded"
                    : outcome.requiresApproval() ? "waiting_approval" : "waiting_tool";
            nodeRunMapper.complete(nodeRunId, state,
                    outcome.outputs(), null, outcome.callId(), LocalDateTime.now(),
                    Duration.between(started, Instant.now()).toMillis(), 0L);
            return outcome;
        } catch (Exception exception) {
            String message = rootMessage(exception);
            nodeRunMapper.complete(nodeRunId, "failed", Map.of(),
                    error("NODE_FAILED", "NODE", node.nodeId(), message), null,
                    LocalDateTime.now(), Duration.between(started, Instant.now()).toMillis(), 0L);
            return NodeOutcome.failed(node.nodeId(), message);
        }
    }

    private NodeOutcome executeNodeWithRetry(WorkflowRunPo run, CompiledWorkflow plan,
                                             WorkflowNode node, Map<String, Object> variables) {
        int maximumRetries = plan.source().executionPolicy().maximumNodeRetries();
        boolean retryAllowed = !(node instanceof WorkflowNode.ToolNode toolNode)
                || toolRegistry.resolve(plan.pinnedTools().get(toolNode.nodeId()))
                .map(tool -> tool.getDefinition().riskProfile().idempotent()).orElse(false);
        NodeOutcome outcome = executeNode(run, plan, node, variables);
        for (int retry = 0; outcome.error() != null && retryAllowed && retry < maximumRetries; retry++) {
            outcome = executeNode(run, plan, node, variables);
        }
        return outcome;
    }

    private NodeOutcome executeToolNode(WorkflowRunPo run, CompiledWorkflow plan,
                                        WorkflowNode.ToolNode node, Map<String, Object> inputs) {
        Map<String, Object> checkpoint = latestState(run.getRunId());
        Map<String, Object> attributes = new LinkedHashMap<>(objectMap(checkpoint.get(STATE_ATTRIBUTES)));
        attributes.put(DefaultToolGateway.ATTR_SOURCE_TYPE, "WORKFLOW");
        attributes.put(DefaultToolGateway.ATTR_SOURCE_ID, run.getRunId());
        put(attributes, DefaultToolGateway.ATTR_BINDING_ID, node.configuration().get("bindingId"));
        put(attributes, DefaultToolGateway.ATTR_WORKSPACE_ID, node.configuration().get("workspaceId"));
        int attempt = nodeRunMapper.selectLatestAttempt(run.getRunId(), node.nodeId())
                .map(WorkflowNodeRunPo::getAttempt).orElse(1);
        ToolExecutionContext context = new ToolExecutionContext(run.getRunId(), run.getRunId(), null,
                run.getTraceId(), null, new ToolPrincipal(run.getOwnerId(), run.getSubjectId(),
                stringSet(checkpoint.get(STATE_ROLES)), stringSet(checkpoint.get(STATE_SCOPES))),
                toInstant(run.getDeadlineAt()), NeverToolCancellation.INSTANCE,
                run.getRunId() + ":" + node.nodeId() + ":" + attempt, null, attributes);
        ToolPolicyOverride override = node.executionPolicy() == null ? null
                : new ToolPolicyOverride(node.executionPolicy().executionMode(), node.executionPolicy().timeout(),
                node.executionPolicy().maxRetries(), node.executionPolicy().retryBackoff(),
                node.executionPolicy().maxOutputTokens(), node.executionPolicy().requiresApproval(),
                node.executionPolicy().allowsResultCache());
        String callId = UUID.randomUUID().toString();
        ToolResult<? extends ToolResponse> result = toolGateway.invoke(new ToolCallRequest(callId,
                plan.pinnedTools().get(node.nodeId()), inputs, context, override)).toCompletableFuture().join();
        if (result instanceof ToolResult.Succeeded<?> succeeded) {
            return NodeOutcome.succeeded(node.nodeId(), callId, outputMap(succeeded.output(), node));
        }
        if (result instanceof ToolResult.Accepted<?> accepted) {
            return NodeOutcome.waiting(node.nodeId(), callId, accepted.taskHandle().taskId(), null, false);
        }
        if (result instanceof ToolResult.Suspended<?> suspended) {
            String taskId = String.valueOf(suspended.metadata().get("taskId"));
            return NodeOutcome.waiting(node.nodeId(), callId, taskId, suspended.resumeToken(), true);
        }
        ToolResult.Unsuccessful<?> failed = (ToolResult.Unsuccessful<?>) result;
        return NodeOutcome.failed(node.nodeId(), failed.error().message());
    }

    private NodeOutcome executeStructuralNode(WorkflowNode node, Map<String, Object> inputs) {
        if (node.type() == WorkflowNodeTypeEnum.START) {
            return NodeOutcome.succeeded(node.nodeId(), null, inputs);
        }
        return NodeOutcome.succeeded(node.nodeId(), null, inputs);
    }

    private PendingOutcome consumePending(WorkflowRunPo run, CompiledWorkflow plan,
                                          Map<String, Object> variables, Set<String> completed,
                                          Map<String, String> pending) {
        for (Map.Entry<String, String> entry : pending.entrySet()) {
            Optional<ToolResult<? extends ToolResponse>> result = toolGateway
                    .findTaskResult(entry.getValue(), run.getOwnerId()).toCompletableFuture().join();
            if (result.isEmpty()) return new PendingOutcome(false, List.of(entry.getKey()), null);
            if (result.get() instanceof ToolResult.Succeeded<?> succeeded) {
                WorkflowNode node = plan.nodes().get(entry.getKey());
                Map<String, Object> outputs = outputMap(succeeded.output(), node);
                completePendingNode(run.getRunId(), entry.getKey(), "succeeded", outputs, null);
                storeOutputs(entry.getKey(), outputs, variables);
                completed.add(entry.getKey());
            } else if (result.get() instanceof ToolResult.Unsuccessful<?> failed) {
                completePendingNode(run.getRunId(), entry.getKey(), "failed", Map.of(),
                        error(failed.error().code(), failed.error().category().name(),
                                entry.getKey(), failed.error().message()));
                return new PendingOutcome(true, List.of(entry.getKey()), failed.error().message());
            }
        }
        return new PendingOutcome(true, List.copyOf(pending.keySet()), null);
    }

    private void completePendingNode(String runId, String nodeId, String status,
                                     Map<String, Object> outputs, Map<String, Object> errorInfo) {
        WorkflowNodeRunPo nodeRun = nodeRunMapper.selectLatestAttempt(runId, nodeId).orElseThrow(() ->
                new IllegalStateException("workflow node run is missing: " + nodeId));
        long latency = nodeRun.getStartedAt() == null ? 0L
                : Duration.between(nodeRun.getStartedAt(), LocalDateTime.now()).toMillis();
        if (nodeRunMapper.complete(nodeRun.getNodeRunId(), status, outputs, errorInfo,
                nodeRun.getCallId(), LocalDateTime.now(), latency, nodeRun.getRowVersion()) != 1) {
            throw new IllegalStateException("workflow node run changed concurrently: " + nodeId);
        }
    }

    private void enableOutgoing(CompiledWorkflow plan, String sourceNodeId,
                                Map<String, Object> variables, Set<String> enabled) {
        plan.edges().stream().filter(edge -> edge.sourceNodeId().equals(sourceNodeId))
                .filter(edge -> condition(edge.conditionExpression(), variables))
                .forEach(edge -> enabled.add(edge.targetNodeId()));
    }

    private boolean dependenciesReady(String nodeId, CompiledWorkflow plan,
                                      Set<String> enabled, Set<String> completed) {
        Set<String> enabledDependencies = new HashSet<>(plan.dependencies().getOrDefault(nodeId, Set.of()));
        enabledDependencies.retainAll(enabled);
        return completed.containsAll(enabledDependencies);
    }

    private Map<String, Object> resolveInputs(WorkflowNode node, Map<String, String> bindings,
                                              Map<String, Object> workflowInputs,
                                              Map<String, Object> variables) {
        if (node.type() == WorkflowNodeTypeEnum.START) return workflowInputs;
        Map<String, Object> result = new LinkedHashMap<>();
        bindings.forEach((name, expression) ->
                result.put(name, resolve(expression, workflowInputs, variables)));
        return Map.copyOf(result);
    }

    private Object resolve(String expression, Map<String, Object> inputs, Map<String, Object> variables) {
        String path = expression == null ? "" : expression.trim();
        if (path.startsWith("${") && path.endsWith("}")) path = path.substring(2, path.length() - 1);
        if (path.startsWith("$")) path = path.substring(1);
        if (path.startsWith("inputs.")) return nested(inputs, path.substring(7));
        return nested(variables, path);
    }

    private Object nested(Map<String, Object> source, String path) {
        if (source.containsKey(path)) return source.get(path);
        Object current = source;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current;
    }

    private boolean condition(String expression, Map<String, Object> variables) {
        if (expression == null || expression.isBlank() || "true".equalsIgnoreCase(expression.trim())) return true;
        if ("false".equalsIgnoreCase(expression.trim())) return false;
        String value = expression.trim();
        if (value.contains("==")) {
            String[] parts = value.split("==", 2);
            return Objects.equals(String.valueOf(resolve(parts[0].trim(), Map.of(), variables)),
                    stripQuotes(parts[1].trim()));
        }
        Object resolved = resolve(value, Map.of(), variables);
        return Boolean.TRUE.equals(resolved) || "true".equalsIgnoreCase(String.valueOf(resolved));
    }

    private Map<String, Object> outputMap(ToolResponse response, WorkflowNode node) {
        Object value = response instanceof DynamicToolResponse dynamic ? dynamic.value() :
                objectMapper.convertValue(response, Object.class);
        if (value instanceof Map<?, ?> map) return objectMap(map);
        String outputName = node.outputNames().stream().findFirst().orElse("result");
        return Map.of(outputName, value == null ? "" : value);
    }

    private void storeOutputs(String nodeId, Map<String, Object> outputs, Map<String, Object> variables) {
        variables.put(nodeId, outputs);
        outputs.forEach((name, value) -> variables.put(nodeId + "." + name, value));
    }

    private Map<String, Object> workflowOutputs(CompiledWorkflow plan, Map<String, Object> variables,
                                                Set<String> completed) {
        for (String id : plan.executionOrder()) {
            if (!completed.contains(id)) continue;
            WorkflowNode node = plan.nodes().get(id);
            if (node instanceof WorkflowNode.EndNode) {
                return resolveInputs(node,
                        plan.variableMappings().getOrDefault(node.nodeId(), node.inputBindings()),
                        Map.of(), variables);
            }
        }
        return Map.of();
    }

    private void checkpoint(WorkflowRunPo run, Map<String, Object> state, Set<String> completed,
                            Set<String> enabled, Map<String, Object> variables,
                            Map<String, String> pending) {
        state.put(STATE_COMPLETED, List.copyOf(completed));
        state.put(STATE_ENABLED, List.copyOf(enabled));
        state.put(STATE_VARIABLES, Map.copyOf(variables));
        state.put(STATE_PENDING, Map.copyOf(pending));
        long sequence = checkpoints.findLatest(run.getRunId()).map(value -> value.sequence() + 1).orElse(0L);
        saveCheckpoint(run.getRunId(), sequence, state);
        run.setVariables(Map.copyOf(variables));
        update(run);
    }

    private void saveCheckpoint(String runId, long sequence, Map<String, Object> state) {
        checkpoints.save(new WorkflowCheckpoint(UUID.randomUUID().toString(), runId, sequence,
                Map.copyOf(state), Instant.now()));
    }

    private Map<String, Object> latestState(String runId) {
        return checkpoints.findLatest(runId).map(WorkflowCheckpoint::state).orElseThrow(() ->
                new IllegalStateException("workflow checkpoint is missing"));
    }

    private void release(WorkflowRunPo run, String status, String resumeHash) {
        run.setStatus(status).setResumeTokenHash(resumeHash).setWorkerId(null).setLeaseUntil(null);
        update(run);
    }

    private void releaseLease(WorkflowRunPo run) {
        run.setWorkerId(null).setLeaseUntil(null);
        update(run);
    }

    private void failRun(WorkflowRunPo run, String code, String nodeId, String message) {
        run.setStatus("failed").setErrorInfo(error(code, "NODE", nodeId, message))
                .setActiveNodeIds(Set.of()).setCompletedAt(LocalDateTime.now())
                .setWorkerId(null).setLeaseUntil(null);
        update(run);
    }

    private void timeoutRun(WorkflowRunPo run, String message) {
        run.setStatus("timed_out").setErrorInfo(error("WORKFLOW_TIMEOUT", "TIMEOUT", null, message))
                .setActiveNodeIds(Set.of()).setCompletedAt(LocalDateTime.now())
                .setWorkerId(null).setLeaseUntil(null);
        update(run);
    }

    private void update(WorkflowRunPo run) {
        long expected = run.getRowVersion();
        if (runMapper.updateState(run, expected) != 1) {
            throw new IllegalStateException("workflow run changed concurrently: " + run.getRunId());
        }
        run.setRowVersion(expected + 1);
    }

    private WorkflowRun toDomain(WorkflowRunPo po, String exposedResumeToken) {
        WorkflowError workflowError = po.getErrorInfo() == null ? null : new WorkflowError(
                String.valueOf(po.getErrorInfo().getOrDefault("code", "WORKFLOW_FAILED")),
                parseCategory(po.getErrorInfo().get("category")),
                po.getErrorInfo().get("nodeId") == null ? null : String.valueOf(po.getErrorInfo().get("nodeId")),
                String.valueOf(po.getErrorInfo().getOrDefault("message", "workflow failed")), null, Map.of());
        // 数据库中仅保存 token 摘要；查询运行状态时绝不能把摘要当作恢复凭证泄露给调用方。
        String token = exposedResumeToken != null ? exposedResumeToken
                : po.getResumeTokenHash() == null ? null : "***";
        return new WorkflowRun(po.getRunId(), po.getWorkflowId(), po.getWorkflowVersion(),
                toInstant(po.getCreateTime()), WorkflowRun.Status.valueOf(po.getStatus().toUpperCase()),
                po.getActiveNodeIds(), po.getOutputs(), workflowError, token,
                toInstantNullable(po.getStartedAt()), toInstantNullable(po.getCompletedAt()),
                toInstant(po.getUpdateTime()), po.getRowVersion());
    }

    private Map<String, Object> error(String code, String category, String nodeId, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("category", category);
        result.put("message", message);
        if (nodeId != null) result.put("nodeId", nodeId);
        return Map.copyOf(result);
    }

    private WorkflowError.Category parseCategory(Object value) {
        try {
            return WorkflowError.Category.valueOf(String.valueOf(value).toUpperCase());
        } catch (Exception ignored) {
            return WorkflowError.Category.INTERNAL;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private Map<String, String> pending(Map<String, Object> state) {
        Map<String, String> result = new LinkedHashMap<>();
        objectMap(state.get(STATE_PENDING)).forEach((key, value) -> result.put(key, String.valueOf(value)));
        return result;
    }

    private Set<String> stringSet(Object value) {
        if (!(value instanceof Collection<?> collection)) return new LinkedHashSet<>();
        Set<String> result = new LinkedHashSet<>();
        collection.forEach(item -> result.add(String.valueOf(item)));
        return result;
    }

    private boolean terminal(String status) {
        return Set.of("succeeded", "failed", "cancelled", "timed_out").contains(status);
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(key, value);
    }

    private String stripQuotes(String value) {
        return value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))
                ? value.substring(1, value.length() - 1) : value;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private LocalDateTime toLocal(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }

    private Instant toInstant(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private Instant toInstantNullable(LocalDateTime value) {
        return value == null ? null : toInstant(value);
    }

    @PreDestroy
    public void close() {
        leaseScheduler.close();
    }

    private record NodeOutcome(String nodeId, String callId, Map<String, Object> outputs,
                               String taskId, String resumeToken, boolean requiresApproval, String error) {
        static NodeOutcome succeeded(String nodeId, String callId, Map<String, Object> outputs) {
            return new NodeOutcome(nodeId, callId, outputs, null, null, false, null);
        }

        static NodeOutcome waiting(String nodeId, String callId, String taskId,
                                   String token, boolean approval) {
            return new NodeOutcome(nodeId, callId, Map.of(), taskId, token, approval, null);
        }

        static NodeOutcome failed(String nodeId, String error) {
            return new NodeOutcome(nodeId, null, Map.of(), null, null, false, error);
        }
    }

    private record PendingOutcome(boolean ready, List<String> nodeIds, String error) {
        String nodeId() {
            return nodeIds.isEmpty() ? null : nodeIds.getFirst();
        }
    }
}
