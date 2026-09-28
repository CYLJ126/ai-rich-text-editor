package com.arte.ai.web.controller;

import com.arte.ai.pojo.tool.ToolCallDetail;
import com.arte.ai.pojo.tool.ToolCallPage;
import com.arte.ai.pojo.tool.ToolCallStatistics;
import com.arte.ai.pojo.tool.ToolExecutionTrace;
import com.arte.ai.service.tool.observability.ToolCallQueryService;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 当前用户工具调用明细、统计与轨迹查询接口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@RestController
@RequestMapping("/ai/tool-observability")
@RequiredArgsConstructor
public class ToolObservabilityController {
    private final ToolCallQueryService service;

    @GetMapping("/calls")
    @PreAuthorize("@pcs.check('aiTool:list')")
    public ResultContext<ToolCallPage> calls(@RequestParam(required = false) String toolId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "1") int current,
                                             @RequestParam(defaultValue = "20") int size) {
        return ResultContext.success(service.page(UserContext.getUserName(), toolId, status,
                current, size));
    }

    @GetMapping("/calls/{callId}")
    @PreAuthorize("@pcs.check('aiTool:list')")
    public ResultContext<ToolCallDetail> call(@PathVariable String callId) {
        return ResultContext.success(service.detail(
                UserContext.getUserName(), callId).orElse(null));
    }

    @GetMapping("/statistics")
    @PreAuthorize("@pcs.check('aiTool:list')")
    public ResultContext<ToolCallStatistics> statistics(@RequestParam(required = false) String toolId,
                                                        @RequestParam(defaultValue = "500") int limit) {
        return ResultContext.success(service.statistics(UserContext.getUserName(), toolId, limit));
    }

    @GetMapping("/traces/{traceId}")
    @PreAuthorize("@pcs.check('aiTool:list')")
    public ResultContext<ToolExecutionTrace> trace(@PathVariable String traceId) {
        return ResultContext.success(service.trace(
                UserContext.getUserName(), traceId).orElse(null));
    }
}
