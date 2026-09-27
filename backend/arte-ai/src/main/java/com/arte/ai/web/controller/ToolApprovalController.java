package com.arte.ai.web.controller;

import com.arte.ai.api.tool.security.ToolApprovalService;
import com.arte.ai.mapper.tool.ToolApprovalMapper;
import com.arte.ai.pojo.tool.ApprovalDecisionRequest;
import com.arte.ai.pojo.tool.ToolApprovalDecision;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.concurrent.CompletionStage;

/**
 * 当前用户高风险工具调用的审批接口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@RestController
@RequestMapping("/ai/tool-approvals")
@RequiredArgsConstructor
public class ToolApprovalController {

    private final ToolApprovalService approvalService;
    private final ToolApprovalMapper approvalMapper;

    @GetMapping
    public ResultContext<?> pending() {
        return ResultContext.success(approvalMapper.selectPendingByOwner(UserContext.getUserName()));
    }

    @PostMapping("/{requestId}/decision")
    public CompletionStage<ResultContext<Void>> decide(@PathVariable String requestId,
                                                       @RequestBody ApprovalDecisionRequest request) {
        String owner = UserContext.getUserName();
        approvalMapper.selectByRequestId(requestId)
                .filter(value -> owner.equals(value.getCreateBy()))
                .orElseThrow(() -> new SecurityException("approval request is unavailable"));
        ToolApprovalDecision decision = new ToolApprovalDecision(requestId, request.approved(), owner,
                request.reason(), Instant.now());
        return approvalService.decide(decision).thenApply(ignored -> ResultContext.success());
    }
}
