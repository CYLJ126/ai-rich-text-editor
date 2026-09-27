package com.arte.ai.service.tool.security;

import com.arte.ai.api.tool.ToolRequest;
import com.arte.ai.api.tool.ToolTaskManager;
import com.arte.ai.api.tool.security.ToolApprovalService;
import com.arte.ai.config.ToolExecutionProperties;
import com.arte.ai.mapper.tool.ToolApprovalMapper;
import com.arte.ai.pojo.tool.ToolApprovalDecision;
import com.arte.ai.pojo.tool.ToolApprovalRequest;
import com.arte.ai.pojo.tool.ToolInvocation;
import com.arte.ai.pojo.tool.po.ToolApprovalPo;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 数据库持久化的工具审批服务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Service
@RequiredArgsConstructor
public class DatabaseToolApprovalService implements ToolApprovalService {

    private static final java.util.regex.Pattern SENSITIVE_KEY = java.util.regex.Pattern.compile(
            "(?i).*(password|secret|token|api[-_]?key|private[-_]?key|credential|authorization|cookie).*"
    );

    private final ToolApprovalMapper approvalMapper;
    private final ObjectMapper objectMapper;
    private final ToolExecutionProperties properties;
    private final ObjectProvider<ToolTaskManager> taskManagerProvider;

    @Override
    public CompletionStage<ToolApprovalRequest> requestApproval(
            ToolInvocation<? extends ToolRequest> invocation) {
        String requestId = UUID.randomUUID().toString();
        String digest = digest(invocation.request());
        Instant expiresAt = Instant.now().plus(properties.getApprovalTimeout());
        Map<String, Object> displayArguments = redact(
                objectMapper.convertValue(invocation.request(), java.util.Map.class));
        ToolApprovalRequest request = new ToolApprovalRequest(requestId, invocation.callId(),
                invocation.tool(), digest, "Approve tool invocation " + invocation.tool(),
                expiresAt, displayArguments);
        ToolApprovalPo po = new ToolApprovalPo()
                .setRequestId(requestId)
                .setCallId(invocation.callId())
                .setWorkflowRunId(invocation.context().workflowRunId())
                .setToolId(invocation.tool().namespace() + ":" + invocation.tool().name())
                .setToolVersion(invocation.tool().version())
                .setArgumentsDigest(digest)
                .setDisplayArguments(request.displayArguments())
                .setSummary(request.summary())
                .setStatus("pending")
                .setExpiresAt(toLocal(expiresAt));
        po.setCreateBy(invocation.context().principal().ownerId());
        po.setUpdateBy(invocation.context().principal().ownerId());
        approvalMapper.insert(po);
        return CompletableFuture.completedFuture(request);
    }

    @Override
    public CompletionStage<Void> decide(ToolApprovalDecision decision) {
        String status = decision.approved() ? "approved" : "rejected";
        int changed = approvalMapper.decideIfPending(decision.requestId(), status,
                decision.approverId(), decision.reason(), toLocal(decision.decidedAt()));
        if (changed != 1) {
            throw new IllegalStateException("approval is missing, expired or already decided: "
                    + decision.requestId());
        }
        approvalMapper.selectByRequestId(decision.requestId()).ifPresent(approval -> {
            if (approval.getTaskId() == null) {
                return;
            }
            ToolTaskManager manager = taskManagerProvider.getIfAvailable();
            if (manager == null) {
                throw new IllegalStateException("tool task manager is unavailable");
            }
            if (decision.approved()) {
                manager.resumeApproved(approval.getTaskId()).toCompletableFuture().join();
            } else {
                manager.terminateApproval(approval.getTaskId(), "approval rejected: "
                        + safeReason(decision.reason())).toCompletableFuture().join();
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Optional<ToolApprovalDecision> findDecision(String requestId) {
        return approvalMapper.selectByRequestId(requestId)
                .filter(po -> "approved".equalsIgnoreCase(po.getStatus())
                        || "rejected".equalsIgnoreCase(po.getStatus()))
                .map(po -> new ToolApprovalDecision(po.getRequestId(),
                        "approved".equalsIgnoreCase(po.getStatus()), po.getApproverId(),
                        po.getDecisionReason(), toInstant(po.getDecidedAt())));
    }

    @Override
    public boolean matchesArguments(String requestId, ToolRequest request) {
        return approvalMapper.selectByRequestId(requestId)
                .map(value -> MessageDigest.isEqual(
                        value.getArgumentsDigest().getBytes(StandardCharsets.US_ASCII),
                        digest(request).getBytes(StandardCharsets.US_ASCII)))
                .orElse(false);
    }

    @Override
    public void attachTask(String requestId, String taskId) {
        if (approvalMapper.attachTask(requestId, taskId) != 1) {
            throw new IllegalStateException("approval is not pending or already attached: " + requestId);
        }
    }

    @Scheduled(fixedDelayString = "${arte.ai.tool.execution.approval-expiry-scan-interval:30s}")
    public void expireApprovals() {
        LocalDateTime now = LocalDateTime.now();
        approvalMapper.expirePending(now);
        ToolTaskManager manager = taskManagerProvider.getIfAvailable();
        if (manager == null) {
            return;
        }
        // 决策写入与任务恢复不是同一数据库事务；定时对账确保节点在两步之间宕机后仍可收敛。
        for (ToolApprovalPo approval : approvalMapper.selectActionable(100)) {
            if ("approved".equalsIgnoreCase(approval.getStatus())) {
                manager.resumeApproved(approval.getTaskId()).toCompletableFuture().join();
            } else {
                manager.terminateApproval(approval.getTaskId(),
                                "expired".equalsIgnoreCase(approval.getStatus())
                                        ? "approval expired" : "approval rejected: "
                                        + safeReason(approval.getDecisionReason()))
                        .toCompletableFuture().join();
            }
        }
    }

    private String digest(Object value) {
        try {
            byte[] bytes = objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to digest approval arguments", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> redact(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key,
                SENSITIVE_KEY.matcher(key).matches() ? "***" : redactValue(value)));
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return redact((Map<String, Object>) map);
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::redactValue).toList();
        }
        return value;
    }

    private String safeReason(String value) {
        return value == null || value.isBlank() ? "no reason provided" : value;
    }

    private LocalDateTime toLocal(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }

    private Instant toInstant(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toInstant();
    }
}
