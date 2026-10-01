package com.arte.ai.web.controller;

import com.arte.ai.api.tool.workflow.WorkflowManager;
import com.arte.ai.api.tool.workflow.WorkflowRunner;
import com.arte.ai.common.enums.tool.NeverToolCancellation;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.service.tool.workflow.WorkflowPersistenceCodec;
import com.arte.ai.service.tool.workflow.WorkflowQueryService;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final WorkflowRunner runner;
    private final WorkflowPersistenceCodec codec;
    private final WorkflowQueryService queryService;

    @GetMapping
    @PreAuthorize("@pcs.check('aiTool:workflow')")
    public ResultContext<WorkflowSummaryPage> workflows(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size) {
        return ResultContext.success(queryService.workflows(
                UserContext.getUserName(), keyword, status, current, size));
    }

    @PostMapping("/drafts")
    @PreAuthorize("@pcs.check('aiTool:workflow')")
    public ResultContext<WorkflowVersionView> saveDraft(@RequestBody WorkflowDraftRequest request) {
        String owner = UserContext.getUserName();
        manager.saveDraft(principal(), definition(request), request.expectedRowVersion());
        return ResultContext.success(queryService.version(owner, request.workflowId(), request.version())
                .orElseThrow(() -> new IllegalStateException("saved workflow version is unavailable")));
    }

    @PostMapping("/validate")
    @PreAuthorize("@pcs.check('aiTool:workflow')")
    public ResultContext<WorkflowValidationResult> validate(@RequestBody WorkflowDraftRequest request) {
        return ResultContext.success(manager.validate(principal(), definition(request)));
    }

    @PostMapping("/{workflowId}/versions/{version}/publish")
    @PreAuthorize("@pcs.check('aiTool:workflow')")
    public ResultContext<CompiledWorkflow> publish(@PathVariable String workflowId,
                                                   @PathVariable String version,
                                                   @RequestParam long expectedRowVersion) {
        return ResultContext.success(manager.publish(principal(), workflowId,
                version, expectedRowVersion));
    }

    @GetMapping("/{workflowId}/versions")
    @PreAuthorize("@pcs.check('aiTool:workflow')")
    public ResultContext<java.util.List<WorkflowVersionView>> versions(@PathVariable String workflowId) {
        return ResultContext.success(queryService.versions(UserContext.getUserName(), workflowId));
    }

    @GetMapping("/{workflowId}/versions/{version}")
    @PreAuthorize("@pcs.check('aiTool:workflow')")
    public ResultContext<WorkflowVersionView> version(@PathVariable String workflowId,
                                                      @PathVariable String version) {
        return ResultContext.success(queryService.version(UserContext.getUserName(), workflowId, version)
                .orElse(null));
    }

    @PostMapping("/runs")
    @PreAuthorize("@pcs.check('aiTool:workflow')")
    public CompletionStage<ResultContext<WorkflowRunActionResult>> start(@RequestBody WorkflowStartRequest request) {
        ToolPrincipal principal = principal();
        String owner = principal.ownerId();
        queryService.version(owner, request.workflowId(), request.version())
                .filter(value -> "published".equals(value.lifecycleState()))
                .orElseThrow(() -> new IllegalArgumentException("only published workflow versions can run"));
        WorkflowDefinition definition = manager.find(owner, request.workflowId(), request.version())
                .orElseThrow(() -> new IllegalArgumentException("workflow version is unavailable"));
        int maximumSteps = request.maximumSteps() == null
                ? definition.executionPolicy().maximumSteps() : request.maximumSteps();
        if (maximumSteps > definition.executionPolicy().maximumSteps()) {
            throw new IllegalArgumentException("maximumSteps cannot exceed the published workflow budget");
        }
        ToolExecutionContext toolContext = new ToolExecutionContext(UUID.randomUUID().toString(),
                null, null, UUID.randomUUID().toString(), null,
                principal,
                Instant.now().plus(definition.executionPolicy().timeout()),
                NeverToolCancellation.INSTANCE, null, null, Map.of());
        WorkflowExecutionContext context = new WorkflowExecutionContext(toolContext, request.inputs(),
                request.variables(), maximumSteps);
        return runner.start(request.workflowId(), request.version(), context).thenApply(run ->
                ResultContext.success(actionResult(owner, run)));
    }

    @GetMapping("/runs")
    @PreAuthorize("@pcs.check('aiTool:workflow')")
    public ResultContext<WorkflowRunPage> runs(
            @RequestParam(required = false) String workflowId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size) {
        return ResultContext.success(queryService.runs(UserContext.getUserName(), workflowId,
                status, current, size));
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize("@pcs.check('aiTool:workflow')")
    public ResultContext<WorkflowRunDetailView> run(@PathVariable String runId) {
        return ResultContext.success(queryService.runDetail(UserContext.getUserName(), runId).orElse(null));
    }

    @PostMapping("/runs/{runId}/cancel")
    @PreAuthorize("@pcs.check('aiTool:workflow')")
    public CompletionStage<ResultContext<WorkflowRunActionResult>> cancel(@PathVariable String runId) {
        String owner = UserContext.getUserName();
        return runner.cancel(runId, owner).thenApply(run ->
                ResultContext.success(actionResult(owner, run)));
    }

    @PostMapping("/runs/resume")
    @PreAuthorize("@pcs.check('aiTool:workflow')")
    public CompletionStage<ResultContext<WorkflowRunActionResult>> resume(@RequestBody ToolResumeRequest request) {
        String owner = UserContext.getUserName();
        return runner.resume(request.resumeToken(), owner)
                .thenApply(run -> ResultContext.success(actionResult(owner, run)));
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

    private WorkflowRunActionResult actionResult(String owner, WorkflowRun run) {
        WorkflowRunView view = queryService.run(owner, run.runId())
                .orElseThrow(() -> new IllegalStateException("workflow run is unavailable"));
        return new WorkflowRunActionResult(view, run.resumeToken());
    }

    private Set<String> copy(Collection<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }
}
