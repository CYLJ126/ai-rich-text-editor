package com.arte.ai.web.controller;

import com.arte.ai.api.tool.ToolBindingManager;
import com.arte.ai.pojo.tool.ResolvedToolBinding;
import com.arte.ai.pojo.tool.ToolBindingCommand;
import com.arte.ai.pojo.tool.ToolBindingView;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 当前用户或工作空间的工具版本绑定接口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@RestController
@RequestMapping("/ai/tool-bindings")
@RequiredArgsConstructor
public class ToolBindingController {

    private final ToolBindingManager bindingManager;

    @PostMapping
    public ResultContext<ResolvedToolBinding> save(@RequestBody ToolBindingCommand command) {
        return ResultContext.success(bindingManager.save(UserContext.getUserName(), command));
    }

    @GetMapping
    public ResultContext<List<ToolBindingView>> list(
            @RequestParam(required = false) String workspaceId) {
        return ResultContext.success(bindingManager.list(UserContext.getUserName(), workspaceId));
    }
}
