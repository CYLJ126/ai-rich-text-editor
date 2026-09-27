package com.arte.ai.web.controller;

import com.arte.ai.api.tool.ToolLifecycleManager;
import com.arte.ai.api.tool.ToolProviderManager;
import com.arte.ai.api.tool.ToolRegistry;
import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.service.tool.ToolManagementQueryService;
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
    private final ToolManagementQueryService queryService;

    @GetMapping("/providers")
    public ResultContext<List<ToolProviderView>> providers(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String providerType) {
        return ResultContext.success(queryService.listProviders(status, providerType));
    }

    @GetMapping("/providers/{providerId}")
    public ResultContext<ToolProviderView> provider(@PathVariable String providerId) {
        return ResultContext.success(queryService.findProvider(providerId).orElse(null));
    }

    @GetMapping("/catalog")
    public ResultContext<ToolCatalogPage> catalog(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String providerId,
            @RequestParam(required = false) ToolLifecycleStateEnum lifecycleState,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size) {
        return ResultContext.success(queryService.catalog(keyword, providerId, lifecycleState, current, size));
    }

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

    @GetMapping("/{namespace}/{name}/{version}/management")
    public ResultContext<ToolVersionDetailView> versionDetail(
            @PathVariable String namespace,
            @PathVariable String name,
            @PathVariable String version) {
        return ResultContext.success(queryService.findVersion(
                new ToolReference(namespace, name, version)).orElse(null));
    }

    @GetMapping("/{namespace}/{name}/versions")
    public ResultContext<List<ToolVersionView>> listVersions(@PathVariable String namespace,
                                                             @PathVariable String name) {
        return ResultContext.success(lifecycleManager.listVersions(namespace, name));
    }
}
