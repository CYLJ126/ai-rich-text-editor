package com.arte.ai.web.controller;

import com.arte.ai.api.tool.workflow.WorkflowCompiler;
import com.arte.ai.api.tool.workflow.WorkflowManager;
import com.arte.ai.api.tool.workflow.WorkflowRunner;
import com.arte.ai.common.enums.tool.NeverToolCancellation;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.service.tool.workflow.WorkflowPersistenceCodec;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * 工作流定义、发布和运行 REST 入口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@RestController
@RequestMapping("/ai/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowManager manager;
    private final WorkflowCompiler compiler;
    private final WorkflowRunner runner;
    private final WorkflowPersistenceCodec codec;

    @PostMapping("/drafts")
    public ResultContext<WorkflowDefinition> saveDraft(@RequestBody WorkflowDraftRequest request) {
        return ResultContext.success(manager.saveDraft(principal(), definition(request)));
    }

    @PostMapping("/validate")
    public ResultContext<WorkflowValidationResult> validate(@RequestBody WorkflowDraftRequest request) {
        return ResultContext.success(manager.validate(principal(), definition(request)));
    }

    @PostMapping("/{workflowId}/versions/{version}/publish")
    public ResultContext<CompiledWorkflow> publish(@PathVariable String workflowId,
                                                   @PathVariable String version,
                                                   @RequestParam long expectedRowVersion) {
        return ResultContext.success(manager.publish(principal(), workflowId,
                version, expectedRowVersion));
    }

    @GetMapping("/{workflowId}/versions")
    public ResultContext<?> versions(@PathVariable String workflowId) {
        return ResultContext.success(manager.listVersions(UserContext.getUserName(), workflowId));
    }

    @PostMapping("/runs")
    public CompletionStage<ResultContext<WorkflowRun>> start(@RequestBody WorkflowStartRequest request) {
        ToolPrincipal principal = principal();
        String owner = principal.ownerId();
        WorkflowDefinition definition = manager.find(owner, request.workflowId(), request.version())
                .orElseThrow(() -> new IllegalArgumentException("workflow version is unavailable"));
        ToolExecutionContext toolContext = new ToolExecutionContext(UUID.randomUUID().toString(),
                null, null, UUID.randomUUID().toString(), null,
                principal,
                Instant.now().plus(definition.executionPolicy().timeout()),
                NeverToolCancellation.INSTANCE, null, null, Map.of());
        WorkflowExecutionContext context = new WorkflowExecutionContext(toolContext, request.inputs(),
                request.variables(), request.maximumSteps() == null
                ? definition.executionPolicy().maximumSteps() : request.maximumSteps());
        return runner.start(compiler.compile(definition), context).thenApply(ResultContext::success);
    }

    @GetMapping("/runs/{runId}")
    public CompletionStage<ResultContext<?>> run(@PathVariable String runId) {
        return runner.findRun(runId, UserContext.getUserName()).thenApply(ResultContext::success);
    }

    @PostMapping("/runs/{runId}/cancel")
    public CompletionStage<ResultContext<WorkflowRun>> cancel(@PathVariable String runId) {
        return runner.cancel(runId, UserContext.getUserName()).thenApply(ResultContext::success);
    }

    @PostMapping("/runs/resume")
    public CompletionStage<ResultContext<WorkflowRun>> resume(@RequestBody ToolResumeRequest request) {
        return runner.resume(request.resumeToken()).thenApply(ResultContext::success);
    }

    private WorkflowDefinition definition(WorkflowDraftRequest request) {
        return new WorkflowDefinition(request.workflowId(), request.version(), request.name(),
                request.description(), codec.decodeSchema(request.inputSchema()),
                codec.decodeSchema(request.outputSchema()), codec.decodeNodes(request.nodes()),
                codec.decodeEdges(request.edges()), request.tags(), codec.decodePolicy(request.executionPolicy()));
    }

    private ToolPrincipal principal() {
        var user = UserContext.getUserOnlineInfo();
        return new ToolPrincipal(user.getUserName(), user.getUserName(),
                copy(user.getRoles()), copy(user.getMenuOperations()));
    }

    private Set<String> copy(Collection<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }
}
