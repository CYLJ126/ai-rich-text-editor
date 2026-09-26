package com.arte.ai.api.tool;

import com.arte.ai.pojo.tool.ToolDefinition;
import com.arte.ai.pojo.tool.ToolReference;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 工具来源抽象。可对应本地 Bean、MCP Server、HTTP 服务或 Agent-as-Tool。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolProvider {

    String getProviderId();

    List<ToolDefinition> listDefinitions();

    Optional<Tool<?, ?>> resolve(ToolReference reference);

    CompletionStage<Void> refresh();
}
