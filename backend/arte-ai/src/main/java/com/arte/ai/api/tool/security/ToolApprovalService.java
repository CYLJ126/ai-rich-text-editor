package com.arte.ai.api.tool.security;

import com.arte.ai.api.tool.ToolRequest;
import com.arte.ai.pojo.tool.ToolApprovalDecision;
import com.arte.ai.pojo.tool.ToolApprovalRequest;
import com.arte.ai.pojo.tool.ToolInvocation;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 工具审批与暂停/恢复契约。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolApprovalService {

    CompletionStage<ToolApprovalRequest> requestApproval(ToolInvocation<? extends ToolRequest> invocation);

    CompletionStage<Void> decide(ToolApprovalDecision decision);

    Optional<ToolApprovalDecision> findDecision(String requestId);
}
