package com.arte.ai.web.controller;

import com.arte.ai.pojo.tool.ToolCallStatistics;
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
    public ResultContext<?> calls(@RequestParam(required = false) String toolId,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "100") int limit) {
        return ResultContext.success(service.details(UserContext.getUserName(), toolId, status, limit));
    }

    @GetMapping("/calls/{callId}")
    @PreAuthorize("@pcs.check('aiTool:list')")
    public ResultContext<?> call(@PathVariable String callId) {
        return ResultContext.success(service.detail(UserContext.getUserName(), callId));
    }

    @GetMapping("/statistics")
    @PreAuthorize("@pcs.check('aiTool:list')")
    public ResultContext<ToolCallStatistics> statistics(@RequestParam(required = false) String toolId,
                                                        @RequestParam(defaultValue = "500") int limit) {
        return ResultContext.success(service.statistics(UserContext.getUserName(), toolId, limit));
    }

    @GetMapping("/traces/{traceId}")
    @PreAuthorize("@pcs.check('aiTool:list')")
    public ResultContext<?> trace(@PathVariable String traceId) {
        return ResultContext.success(service.trace(UserContext.getUserName(), traceId));
    }
}
