package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.ExecutionTarget;

import java.io.Serializable;
import java.util.Objects;

/**
 * 工具产品契约与固定提供者、执行目标一起冻结；不承载发布状态或本次调用参数。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 13:52 ✾
 */
public record ToolDefinitionRevision(ToolDefinition tool, DefinitionRef provider,
                                     ExecutionTarget target,
                                     @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) DefinitionRef protocol) implements Serializable {
    public ToolDefinitionRevision(ToolDefinition tool, DefinitionRef provider, ExecutionTarget target) {
        this(tool, provider, target, null);
    }

    public static final DefinitionRef MCP_PROTOCOL = new DefinitionRef("protocol", "mcp-streamable-http", "2025-06-18");

    public boolean mcp() {
        return MCP_PROTOCOL.equals(protocol);
    }

    public ToolDefinitionRevision {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(provider, "provider").requireType("tool-provider");
        ContractChecks.require(provider.id().equals(tool.providerId()), "Tool provider identity mismatch");
        Objects.requireNonNull(target, "target");
        if (protocol != null) {
            protocol.requireType("protocol");
            ContractChecks.require(target instanceof ExecutionTarget.Remote && protocol.equals(MCP_PROTOCOL)
                    && tool.price() == null, "Only reviewed free MCP protocol is supported");
        }
    }
}
