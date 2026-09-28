package com.arte.ai.service.tool.workflow;

import com.arte.ai.mapper.tool.*;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.pojo.tool.po.*;
import com.arte.ai.service.tool.security.ToolDataSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 当前用户工作流目录、版本和运行记录的只读查询服务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/28 ✾
 */
@Service
@RequiredArgsConstructor
public class WorkflowQueryService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final Set<String> WORKFLOW_STATES = Set.of("draft", "published", "deprecated", "disabled");
    private static final Set<String> RUN_STATES = Set.of("created", "running", "waiting_tool",
            "waiting_approval", "paused", "succeeded", "failed", "cancelled", "timed_out");

    private final WorkflowMapper workflows;
    private final WorkflowVersionMapper versions;
    private final WorkflowRunMapper runs;
    private final WorkflowNodeRunMapper nodeRuns;
    private final WorkflowCheckpointMapper checkpoints;
    private final ToolDataSanitizer sanitizer;

    public WorkflowSummaryPage workflows(String ownerId, String keyword, String status,
                                         int current, int size) {
        String normalizedKeyword = text(keyword);
        String normalizedStatus = state(status, WORKFLOW_STATES, "workflow");
        Page page = page(current, size);
        var records = workflows.selectOwnedPage(ownerId, normalizedKeyword, normalizedStatus,
                page.offset(), page.size()).stream().map(this::summary).toList();
        return new WorkflowSummaryPage(records,
                workflows.countOwned(ownerId, normalizedKeyword, normalizedStatus),
                page.current(), page.size());
    }

    public Optional<WorkflowVersionView> version(String ownerId, String workflowId, String version) {
        if (workflows.selectOwned(workflowId, ownerId).isEmpty()) return Optional.empty();
        return versions.selectExact(workflowId, version).map(this::version);
    }

    public java.util.List<WorkflowVersionView> versions(String ownerId, String workflowId) {
        if (workflows.selectOwned(workflowId, ownerId).isEmpty()) return java.util.List.of();
        return versions.selectVersions(workflowId).stream().map(this::version).toList();
    }

    public WorkflowRunPage runs(String ownerId, String workflowId, String status,
                                int current, int size) {
        String normalizedWorkflowId = text(workflowId);
        String normalizedStatus = state(status, RUN_STATES, "workflow run");
        Page page = page(current, size);
        var records = runs.selectOwnedPage(ownerId, normalizedWorkflowId, normalizedStatus,
                page.offset(), page.size()).stream().map(this::run).toList();
        return new WorkflowRunPage(records,
                runs.countOwned(ownerId, normalizedWorkflowId, normalizedStatus),
                page.current(), page.size());
    }

    public Optional<WorkflowRunDetailView> runDetail(String ownerId, String runId) {
        return runs.selectOwned(runId, ownerId).map(run -> {
            var nodes = nodeRuns.selectByRunId(runId).stream().map(this::nodeRun).toList();
            WorkflowCheckpointPo checkpoint = checkpoints.selectLatest(runId).orElse(null);
            return new WorkflowRunDetailView(run(run), nodes,
                    checkpoint == null || checkpoint.getSequence() == null ? 0 : checkpoint.getSequence(),
                    checkpoint == null ? java.util.Map.of() : sanitizer.sanitize(checkpoint.getState()));
        });
    }

    public Optional<WorkflowRunView> run(String ownerId, String runId) {
        return runs.selectOwned(runId, ownerId).map(this::run);
    }

    private WorkflowSummaryView summary(WorkflowPo value) {
        return new WorkflowSummaryView(value.getWorkflowId(), value.getName(), value.getDescription(),
                value.getLatestVersion(), value.getLifecycleState().getValue(),
                instant(value.getCreateTime()), instant(value.getUpdateTime()));
    }

    private WorkflowVersionView version(WorkflowVersionPo value) {
        var safeNodes = value.getNodes() == null ? java.util.List.<java.util.Map<String, Object>>of()
                : value.getNodes().stream().map(sanitizer::sanitize).toList();
        return new WorkflowVersionView(value.getWorkflowId(), value.getVersion(), value.getName(),
                value.getDescription(), value.getTags(), value.getInputSchema(), value.getOutputSchema(),
                value.getExecutionPolicy(), safeNodes, value.getEdges(), value.getCompiledPlan(),
                value.getPinnedTools(), value.getEntryNodeId(), value.getChecksum(),
                value.getLifecycleState().getValue(), instant(value.getPublishedAt()),
                number(value.getRowVersion()), instant(value.getCreateTime()), instant(value.getUpdateTime()));
    }

    private WorkflowRunView run(WorkflowRunPo value) {
        return new WorkflowRunView(value.getRunId(), value.getWorkflowId(), value.getWorkflowVersion(),
                value.getTraceId(), value.getStatus().toUpperCase(Locale.ROOT), sanitizer.sanitize(value.getInputs()),
                sanitizer.sanitize(value.getVariables()), value.getActiveNodeIds(),
                sanitizer.sanitize(value.getOutputs()), sanitizer.sanitize(value.getErrorInfo()),
                integer(value.getMaximumSteps()), integer(value.getCurrentSteps()),
                instant(value.getStartedAt()), instant(value.getCompletedAt()), instant(value.getDeadlineAt()),
                number(value.getRowVersion()), instant(value.getCreateTime()), instant(value.getUpdateTime()));
    }

    private WorkflowNodeRunView nodeRun(WorkflowNodeRunPo value) {
        return new WorkflowNodeRunView(value.getNodeRunId(), value.getNodeId(), value.getNodeType().getValue(),
                integer(value.getAttempt()), value.getStatus().toUpperCase(Locale.ROOT), value.getCallId(),
                sanitizer.sanitize(value.getInputs()), sanitizer.sanitize(value.getOutputs()),
                sanitizer.sanitize(value.getErrorInfo()), instant(value.getStartedAt()),
                instant(value.getCompletedAt()), value.getLatencyMs());
    }

    private Page page(int current, int size) {
        int safeCurrent = Math.max(1, current);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        return new Page(safeCurrent, safeSize, (long) (safeCurrent - 1) * safeSize);
    }

    private String state(String value, Set<String> supported, String type) {
        String normalized = text(value);
        if (normalized == null) return null;
        normalized = normalized.toLowerCase(Locale.ROOT).replace('-', '_');
        if (!supported.contains(normalized)) {
            throw new IllegalArgumentException("unsupported " + type + " status: " + value);
        }
        return normalized;
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int integer(Integer value) {
        return value == null ? 0 : value;
    }

    private long number(Long value) {
        return value == null ? 0 : value;
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private record Page(int current, int size, long offset) {
    }
}
