package com.arte.ainew.spi.persistence;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.ToolDefinitionRevision;
import com.arte.ainew.pojo.tool.ToolProviderDefinition;
import com.arte.ainew.pojo.tool.ToolSchemaDefinition;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 平台受管工具目录的底层版本存储
 * <p>
 * 只由已授权的管理／发现服务调用，不作为公共入口。
 * 不选择 latest、不发布工具、不执行适配；数据库故障传播 onError，不伪装为冲突。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 13:52 ✾
 */
public interface ToolCatalogStore {
    /**
     * 一批固定事实原子登记；同引用同内容重放，任一漂移或缺依赖则整批不写业务记录。
     */
    Mono<StoreOutcome<Batch>> put(Batch batch);

    Mono<ToolProviderDefinition> findProvider(DefinitionRef provider);

    Mono<ToolSchemaDefinition> findSchema(DefinitionRef schema);

    Mono<ToolDefinitionRevision> findTool(DefinitionRef tool);

    /**
     * 游标为不透明的存储位置，不表达版本新旧；管理服务仍须独立过滤当前可见范围。
     */
    Mono<Page<ToolProviderDefinition>> listProviders(String afterKey, int limit);

    Mono<Page<ToolDefinitionRevision>> listTools(DefinitionRef provider, String afterKey, int limit);

    record Batch(List<ToolProviderDefinition> providers, List<ToolSchemaDefinition> schemas,
                 List<ToolDefinitionRevision> tools) {
        public Batch {
            providers = ContractChecks.list(providers, "providers", 0, 32);
            schemas = ContractChecks.list(schemas, "schemas", 0, 128);
            tools = ContractChecks.list(tools, "tools", 0, 128);
            ContractChecks.require(!providers.isEmpty() || !schemas.isEmpty() || !tools.isEmpty(), "Empty tool catalog batch");
            ContractChecks.unique(providers.stream().map(ToolProviderDefinition::definition).toList(), "provider references");
            ContractChecks.unique(schemas.stream().map(ToolSchemaDefinition::definition).toList(), "schema references");
            ContractChecks.unique(tools.stream().map(t -> t.tool().definition()).toList(), "tool references");
        }
    }

    record Page<T>(List<T> items, String nextCursor) {
        public Page {
            items = ContractChecks.list(items, "items", 0, 100);
            if (nextCursor != null) ContractChecks.digest(nextCursor, "nextCursor");
        }
    }
}
