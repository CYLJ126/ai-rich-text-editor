package com.arte.ai.web.controller;

import com.arte.ai.api.tool.ToolLifecycleManager;
import com.arte.ai.api.tool.ToolProviderManager;
import com.arte.ai.api.tool.ToolRegistry;
import com.arte.ai.pojo.tool.ToolDefinition;
import com.arte.ai.pojo.tool.ToolProviderSyncResult;
import com.arte.ai.pojo.tool.ToolQuery;
import com.arte.ai.pojo.tool.ToolReference;
import com.arte.core.pojo.ResultContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * AI 工具管理与发现接口
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@RestController
@RequestMapping("/ai/tools")
@RequiredArgsConstructor
public class ToolManagementController {

    private final ToolProviderManager providerManager;
    private final ToolLifecycleManager lifecycleManager;
    private final ToolRegistry registry;

    @PostMapping("/providers/{providerId}/synchronize")
    public CompletionStage<ResultContext<ToolProviderSyncResult>> synchronize(
            @PathVariable String providerId) {
        return providerManager.synchronize(providerId).thenApply(ResultContext::success);
    }

    @PostMapping("/providers/synchronize")
    public CompletionStage<ResultContext<List<ToolProviderSyncResult>>> synchronizeAll() {
        return providerManager.synchronizeAll().thenApply(ResultContext::success);
    }

    @PostMapping("/providers/{providerId}/enable")
    public ResultContext<Boolean> enableProvider(@PathVariable String providerId) {
        providerManager.enable(providerId);
        return ResultContext.success(Boolean.TRUE);
    }

    @PostMapping("/providers/{providerId}/disable")
    public ResultContext<Boolean> disableProvider(
            @PathVariable String providerId,
            @RequestParam(required = false) String reason) {
        providerManager.disable(providerId, reason);
        return ResultContext.success(Boolean.TRUE);
    }

    @PostMapping("/{namespace}/{name}/{version}/publish")
    public ResultContext<Boolean> publish(@PathVariable String namespace,
                                          @PathVariable String name,
                                          @PathVariable String version) {
        lifecycleManager.publish(new ToolReference(namespace, name, version));
        return ResultContext.success(Boolean.TRUE);
    }

    @PostMapping("/{namespace}/{name}/{version}/deprecate")
    public ResultContext<Boolean> deprecate(@PathVariable String namespace,
                                            @PathVariable String name,
                                            @PathVariable String version,
                                            @RequestParam(required = false) String reason) {
        lifecycleManager.deprecate(new ToolReference(namespace, name, version), reason);
        return ResultContext.success(Boolean.TRUE);
    }

    @PostMapping("/{namespace}/{name}/{version}/disable")
    public ResultContext<Boolean> disable(@PathVariable String namespace,
                                          @PathVariable String name,
                                          @PathVariable String version,
                                          @RequestParam(required = false) String reason) {
        lifecycleManager.disable(new ToolReference(namespace, name, version), reason);
        return ResultContext.success(Boolean.TRUE);
    }

    @PostMapping("/search")
    public ResultContext<List<ToolDefinition>> search(@RequestBody(required = false) ToolQuery query) {
        return ResultContext.success(registry.search(query));
    }

    @GetMapping("/{namespace}/{name}/{version}")
    public ResultContext<ToolDefinition> resolve(@PathVariable String namespace,
                                                 @PathVariable String name,
                                                 @PathVariable String version) {
        ToolDefinition definition = registry.resolve(new ToolReference(namespace, name, version))
                .map(tool -> tool.getDefinition())
                .orElse(null);
        return ResultContext.success(definition);
    }
}
