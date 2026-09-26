package com.arte.ai.pojo.tool;

/**
 * 工作流程节点间的有向连接。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record WorkflowEdge(
        String edgeId,
        String sourceNodeId,
        String sourceOutput,
        String targetNodeId,
        String targetInput,
        String conditionExpression
) {

    public WorkflowEdge {
        requireText(edgeId, "edgeId");
        requireText(sourceNodeId, "sourceNodeId");
        requireText(targetNodeId, "targetNodeId");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
