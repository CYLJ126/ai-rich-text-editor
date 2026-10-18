package com.arte.ainew.application.tool.discovery;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.tool.ToolProviderDefinition;
import com.arte.ainew.spi.adapter.BusinessToolAdapter;
import com.arte.ainew.spi.adapter.LocalToolDefinitionSource;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 组合模块显式提供的本地来源与执行器清单；不按引用反射加载、不枚举 Bean。
 * 构造只检查登记结构，不读取来源或调用适配器；实例构造后不可追加登记。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:30
 */
public final class LocalToolRegistry {

    private final List<Registration> registrations;

    public LocalToolRegistry(List<Registration> registrations) {
        this.registrations = ContractChecks.list(registrations, "local tool registrations", 1, 32);
        // 一个节点清单只选择某个提供方的一版；历史版本仍保留在不可变存储中。
        ContractChecks.unique(this.registrations.stream().map(r -> r.provider().definition().id()).toList(), "provider identities");
        ContractChecks.unique(this.registrations.stream().flatMap(r -> r.adapters().keySet().stream()).toList(), "adapter references");
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
        return registrations.stream().map(r -> r.adapters().get(adapter)).filter(Objects::nonNull).findFirst();
    }

    /**
     * 受信代码把来源、提供方契约及执行实例一起登记，不接受用户指定 Bean 名称。
     *
     * @param provider LOCAL 提供方的固定接入版本，其 adapter 标识本来源适配版本。
     * @param source   返回该提供方完整定义的来源对象。
     * @param adapters tool-adapter 固定引用到执行实例的不可变映射，最多 128 项。
     */
    public record Registration(ToolProviderDefinition provider, LocalToolDefinitionSource source,
                               Map<DefinitionRef, BusinessToolAdapter> adapters) {

        public Registration {
            Objects.requireNonNull(provider, "provider");
            ContractChecks.require(provider.kind() == ToolProviderDefinition.Kind.LOCAL, "Local registration requires a LOCAL provider");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(adapters, "adapters");
            ContractChecks.range(adapters.size(), "adapters.size", 0, 128);
            adapters = Map.copyOf(adapters);
            adapters.keySet().forEach(ref -> ref.requireType("tool-adapter"));
        }
    }
}
