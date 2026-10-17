package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.util.Objects;

/**
 * 平台受管提供者的不可变接入契约
 * <p>
 * 适配引用不是 Java 类名，也不证明实现已注册。
 * 活跃版本选择、启停及同步状态由后续目录管理负责，不以此历史版本授予执行资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 13:52 ✾
 */
public record ToolProviderDefinition(DefinitionRef definition, Kind kind, DefinitionRef adapter,
                                     DefinitionRef connection) implements Serializable {

    public enum Kind {LOCAL, HTTP, MCP}

    public ToolProviderDefinition {
        Objects.requireNonNull(definition, "definition").requireType("tool-provider");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(adapter, "adapter").requireType("tool-provider-adapter");
        if (kind == Kind.LOCAL) ContractChecks.require(connection == null, "Local provider cannot have a connection");
        else Objects.requireNonNull(connection, "connection").requireType("connection");
    }
}
