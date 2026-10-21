package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;
import java.util.Objects;

/**
 * 一个固定版本工具的完整定义
 * <p>
 * 将产品契约、定义来源、执行目标及可选协议标记组成不可变材料。
 * 供目录登记、发布审核、绑定解析和执行器匹配使用；自身不执行工具，也不代表已发布或已获当前使用权限。
 * 版本取自各字段中的固定引用，不是个人绑定的 rN 修订号；不承载本次调用参数或运行状态。
 *
 * @param tool     工具产品契约，包含工具标识及版本、名称、输入／输出／配置 Schema、风险、执行策略和可选价格。
 * @param provider 定义来源的固定版本引用，type=tool-provider；其 id 必须与 tool.providerId 一致，引用本身不授予执行资格。
 * @param target   实际执行位置：Local 指向受管适配器版本，Remote 指向受管连接版本及操作键；不直接保存 URL、凭据或执行器实例。
 * @param protocol 可选的显式协议标记，type=protocol；当前仅 MCP 填写 MCP_PROTOCOL，本地及原 HTTP 合同保持 null。
 *                 null 不表示远端没有传输协议，连接的实际传输配置仍由受管连接定义。非空时当前只接受远端、声明免费的 MCP 合同。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 13:52 ✾
 */
public record ToolDefinitionRevision(ToolDefinition tool, DefinitionRef provider, ExecutionTarget target,
                                     @JsonInclude(JsonInclude.Include.NON_NULL) DefinitionRef protocol) implements Serializable {

    /**
     * 兼容本地及原 HTTP 定义的构造方式，不附加 MCP 协议标记。
     */
    public ToolDefinitionRevision(ToolDefinition tool, DefinitionRef provider, ExecutionTarget target) {
        this(tool, provider, target, null);
    }

    /**
     * 当前支持的 MCP 传输及协议版本；该版本独立于工具、提供方和连接各自的发布版本。
     */
    public static final DefinitionRef MCP_PROTOCOL = new DefinitionRef("protocol", "mcp-streamable-http", "2025-06-18");

    /**
     * 按固定协议标记识别 MCP，供执行器路由和快照编码使用；不证明服务可达或当前有权调用。
     */
    public boolean mcp() {
        return MCP_PROTOCOL.equals(protocol);
    }

    /**
     * 仅校验定义结构和字段间约束；实际执行器、发布资格及当前权限由后续流程检查。
     */
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
