package com.arte.ai.api.tool;

import com.arte.ai.pojo.tool.AssistantToolCommand;
import com.arte.ai.pojo.tool.ResolvedAssistantTool;

import java.util.List;

/**
 * AI 助手工具集合管理与模型工具定义生成接口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface AssistantToolManager {

    void replace(String ownerId, String workspaceId, Integer assistantId,
                 List<AssistantToolCommand> tools);

    List<ResolvedAssistantTool> resolveForModel(String ownerId, String workspaceId,
                                                Integer assistantId);

    List<AssistantToolCommand> listConfiguration(String ownerId, Integer assistantId);

    /**
     * 生成只暴露给模型的 Spring AI 工具定义。实际分派时必须使用
     * {@link #resolveForModel(String, String, Integer)} 返回的 bindingId 和固定 ToolReference，
     * 以便调用记录始终保存真实版本。
     */
    default List<org.springframework.ai.tool.definition.ToolDefinition> modelDefinitions(
            String ownerId, String workspaceId, Integer assistantId) {
        return resolveForModel(ownerId, workspaceId, assistantId).stream()
                .map(ResolvedAssistantTool::modelDefinition)
                .toList();
    }
}
