package com.arte.ai.service.tool.security;

import com.arte.ai.api.tool.ToolRequest;
import com.arte.ai.api.tool.security.ToolApprovalService;
import com.arte.ai.mapper.tool.ToolApprovalMapper;
import com.arte.ai.pojo.tool.ToolApprovalDecision;
import com.arte.ai.pojo.tool.ToolApprovalRequest;
import com.arte.ai.pojo.tool.ToolInvocation;
import com.arte.ai.pojo.tool.po.ToolApprovalPo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
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

    private final ToolApprovalMapper approvalMapper;
    private final ObjectMapper objectMapper;

    @Override
    public CompletionStage<ToolApprovalRequest> requestApproval(
            ToolInvocation<? extends ToolRequest> invocation) {
        String requestId = UUID.randomUUID().toString();
        String digest = digest(invocation.request());
        Instant expiresAt = Instant.now().plus(invocation.effectivePolicy().timeout());
        ToolApprovalRequest request = new ToolApprovalRequest(requestId, invocation.callId(),
                invocation.tool(), digest, "Approve tool invocation " + invocation.tool(),
                expiresAt, objectMapper.convertValue(invocation.request(), java.util.Map.class));
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

    private String digest(Object value) {
        try {
            byte[] bytes = objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to digest approval arguments", exception);
        }
    }

    private LocalDateTime toLocal(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }

    private Instant toInstant(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toInstant();
    }
}
