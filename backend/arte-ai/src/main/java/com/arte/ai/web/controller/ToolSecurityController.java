package com.arte.ai.web.controller;

import com.arte.ai.api.tool.security.ToolGuardrail;
import com.arte.ai.pojo.tool.ToolGuardrailView;
import com.arte.core.pojo.ResultContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工具安全执行链的只读接口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/28 ✾
 */
@RestController
@RequestMapping("/ai/tool-security")
public class ToolSecurityController {

    private final List<ToolGuardrail> guardrails;

    public ToolSecurityController(List<ToolGuardrail> guardrails) {
        this.guardrails = List.copyOf(guardrails);
    }

    @GetMapping("/guardrails")
    @PreAuthorize("@pcs.check('aiTool:list')")
    public ResultContext<List<ToolGuardrailView>> guardrails() {
        List<ToolGuardrailView> views = java.util.stream.IntStream.range(0, guardrails.size())
                .mapToObj(index -> {
                    ToolGuardrail guardrail = guardrails.get(index);
                    return new ToolGuardrailView(guardrail.getName(), index + 1,
                            guardrail.getSupportedPhases().stream()
                                    .map(phase -> phase.getValue()).sorted().toList(),
                            guardrail.getClass().getSimpleName(), true);
                }).toList();
        return ResultContext.success(views);
    }
}
