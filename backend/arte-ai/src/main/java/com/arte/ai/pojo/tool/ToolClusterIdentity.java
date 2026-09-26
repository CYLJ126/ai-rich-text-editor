package com.arte.ai.pojo.tool;

/**
 * 当前工具运行节点的集群身份。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolClusterIdentity(String instanceId) {

    public ToolClusterIdentity {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
    }
}
