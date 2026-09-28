package com.arte.ai.web.controller;

import com.arte.ai.api.tool.security.ToolApprovalService;
import com.arte.ai.mapper.tool.ToolApprovalMapper;
import com.arte.ai.pojo.tool.ApprovalDecisionRequest;
import com.arte.ai.pojo.tool.ToolApprovalDecision;
import com.arte.ai.pojo.tool.ToolApprovalPage;
import com.arte.ai.pojo.tool.ToolApprovalView;
import com.arte.ai.service.tool.security.ToolApprovalQueryService;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final ToolApprovalQueryService queryService;

    @GetMapping
    @PreAuthorize("@pcs.check('aiTool:approve')")
    public ResultContext<ToolApprovalPage> list(@RequestParam(defaultValue = "pending") String status,
                                                @RequestParam(defaultValue = "1") int current,
                                                @RequestParam(defaultValue = "20") int size) {
        return ResultContext.success(queryService.listOwned(
                UserContext.getUserName(), status, current, size));
    }

    @GetMapping("/{requestId}")
    @PreAuthorize("@pcs.check('aiTool:approve')")
    public ResultContext<ToolApprovalView> detail(@PathVariable String requestId) {
        return ResultContext.success(queryService.findOwned(
                UserContext.getUserName(), requestId).orElse(null));
    }

    @PostMapping("/{requestId}/decision")
    @PreAuthorize("@pcs.check('aiTool:approve')")
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
