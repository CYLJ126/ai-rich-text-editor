package com.arte.ai.web.controller;

import com.arte.ai.api.tool.AssistantToolManager;
import com.arte.ai.pojo.tool.AssistantToolCommand;
import com.arte.ai.pojo.tool.AssistantToolConfigurationCommand;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI 助手工具集合配置接口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@RestController
@RequestMapping("/ai/assistants/{assistantId}/tools")
@RequiredArgsConstructor
public class AssistantToolController {

    private final AssistantToolManager assistantToolManager;

    @PostMapping
    public ResultContext<Boolean> replace(@PathVariable Integer assistantId,
                                          @RequestBody AssistantToolConfigurationCommand command) {
        assistantToolManager.replace(UserContext.getUserName(), command.workspaceId(),
                assistantId, command.tools());
        return ResultContext.success(Boolean.TRUE);
    }

    @GetMapping("/definitions")
    public ResultContext<List<ToolDefinition>> definitions(
            @PathVariable Integer assistantId,
            @RequestParam(required = false) String workspaceId) {
        return ResultContext.success(assistantToolManager.modelDefinitions(
                UserContext.getUserName(), workspaceId, assistantId));
    }

    @GetMapping
    public ResultContext<List<AssistantToolCommand>> configuration(
            @PathVariable Integer assistantId) {
        return ResultContext.success(assistantToolManager.listConfiguration(
                UserContext.getUserName(), assistantId));
    }
}
