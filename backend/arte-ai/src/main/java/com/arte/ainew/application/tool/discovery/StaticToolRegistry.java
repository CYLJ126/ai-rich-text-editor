package com.arte.ainew.application.tool.discovery;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.tool.ToolDefinitionRevision;
import com.arte.ainew.pojo.tool.ToolProviderDefinition;
import com.arte.ainew.spi.adapter.BusinessToolAdapter;
import com.arte.ainew.spi.adapter.HttpToolAdapter;
import com.arte.ainew.spi.adapter.McpToolAdapter;
import com.arte.ainew.spi.adapter.StaticToolDefinitionSource;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 组合模块显式提供的静态来源与本地／受控 HTTP／MCP 执行器清单；不按引用反射加载、不枚举 Bean。
 * 构造只检查登记结构，不读取来源或调用适配器；实例构造后不可追加登记。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:30
 */
public final class StaticToolRegistry {

    private final List<Registration> registrations;

    public StaticToolRegistry(List<Registration> registrations) {
        this.registrations = ContractChecks.list(registrations, "local tool registrations", 1, 32);
        // 一个节点清单只选择某个提供方的一版；历史版本仍保留在不可变存储中。
        ContractChecks.unique(this.registrations.stream().map(r -> r.provider().definition().id()).toList(), "provider identities");
        ContractChecks.unique(this.registrations.stream().flatMap(r -> r.localAdapters().keySet().stream()).toList(), "adapter references");
    }

    /**
     * 完整的受信登记清单，不是当前主体的已授权工具目录。
     */
    public List<Registration> registrations() {
        return registrations;
    }

    /**
     * 按固定版本查找已登记实现；找到实现不表示已发现、已发布或允许调用。
     *
     * @param adapter 类型为 tool-adapter 的固定版本引用。
     * @return 显式登记的实例；不存在时为空。
     */
    public Optional<BusinessToolAdapter> findAdapter(DefinitionRef adapter) {
        Objects.requireNonNull(adapter, "adapter").requireType("tool-adapter");
        return registrations.stream().map(r -> r.localAdapters().get(adapter)).filter(Objects::nonNull).findFirst();
    }

    /**
     * 静态来源分别登记受控 HTTP／MCP 契约；不按远端列表自动加载执行器或回退协议。
     */
    public Optional<BusinessToolAdapter> executor(ToolDefinitionRevision revision) {
        return registrations.stream().filter(r -> r.provider().definition().equals(revision.provider())).findFirst()
                .flatMap(r -> revision.target() instanceof ExecutionTarget.Local local ? Optional.ofNullable(r.localAdapters().get(local.adapter()))
                        : Optional.ofNullable((revision.mcp() ? r.mcpAdapters() : r.httpAdapters()).get(revision.target()))
                        .filter(a -> a.revision().equals(revision)).map(a -> (BusinessToolAdapter) a))
                .filter(a -> revision.tool().price() == null || a instanceof com.arte.ainew.spi.adapter.PaidToolAdapter paid && paid.revision().equals(revision));
    }

    /**
     * 受信代码把来源、提供方契约及执行实例一起登记，不接受用户指定 Bean 名称。
     *
     * @param provider      LOCAL／HTTP／MCP 提供方的固定接入版本，其 adapter 标识本来源适配版本。
     * @param source        返回该提供方完整定义的来源对象。
     * @param localAdapters 本地 tool-adapter 固定引用到执行实例的不可变映射，最多 128 项。
     */
    public record Registration(ToolProviderDefinition provider, StaticToolDefinitionSource source,
                               Map<DefinitionRef, BusinessToolAdapter> localAdapters,
                               Map<ExecutionTarget.Remote, HttpToolAdapter> httpAdapters,
                               Map<ExecutionTarget.Remote, McpToolAdapter> mcpAdapters) {
        public Registration(ToolProviderDefinition provider, StaticToolDefinitionSource source, Map<DefinitionRef, BusinessToolAdapter> localAdapters,
                            Map<ExecutionTarget.Remote, HttpToolAdapter> httpAdapters) {
            this(provider, source, localAdapters, httpAdapters, Map.of());
        }

        public Registration(ToolProviderDefinition provider, StaticToolDefinitionSource source, Map<DefinitionRef, BusinessToolAdapter> localAdapters) {
            this(provider, source, localAdapters, Map.of(), Map.of());
        }

        public Registration {
            Objects.requireNonNull(provider, "provider");
            ContractChecks.require(provider.kind() == ToolProviderDefinition.Kind.LOCAL || provider.kind() == ToolProviderDefinition.Kind.HTTP || provider.kind() == ToolProviderDefinition.Kind.MCP,
                    "Only static reviewed providers are supported");
            httpAdapters = Map.copyOf(httpAdapters);
            mcpAdapters = Map.copyOf(mcpAdapters);
            ContractChecks.range(mcpAdapters.size(), "mcpAdapters.size", 0, 128);
            ContractChecks.require(provider.kind() == ToolProviderDefinition.Kind.MCP
                    ? localAdapters.isEmpty() && httpAdapters.isEmpty() && !mcpAdapters.isEmpty() : mcpAdapters.isEmpty(), "MCP executor kind mismatch");
            mcpAdapters.forEach((target, adapter) -> ContractChecks.require(adapter.revision().mcp()
                            && target.connection().equals(provider.connection()) && target.equals(adapter.revision().target())
                            && adapter.revision().provider().equals(provider.definition())
                            && com.arte.ainew.pojo.tool.ToolExecutionRules.remoteReadOnly(adapter.revision().tool(), adapter.revision().tool().defaultPolicy()),
                    "MCP adapter must match the fixed reviewed contract"));
            ContractChecks.range(httpAdapters.size(), "httpAdapters.size", 0, 128);
            ContractChecks.require(provider.kind() == ToolProviderDefinition.Kind.HTTP ? localAdapters.isEmpty() && !httpAdapters.isEmpty() : httpAdapters.isEmpty(), "Provider executor kind mismatch");
            httpAdapters.forEach((target, adapter) -> ContractChecks.require(!adapter.revision().mcp() && target.connection().equals(provider.connection())
                            && target.equals(adapter.revision().target()) && adapter.revision().provider().equals(provider.definition())
                            && com.arte.ainew.pojo.tool.ToolExecutionRules.remoteReadOnly(adapter.revision().tool(), adapter.revision().tool().defaultPolicy()),
                    "HTTP adapter must match fixed reviewed read-only contract"));
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(localAdapters, "adapters");
            ContractChecks.range(localAdapters.size(), "adapters.size", 0, 128);
            localAdapters = Map.copyOf(localAdapters);
            localAdapters.keySet().forEach(ref -> ref.requireType("tool-adapter"));
        }
    }
}
