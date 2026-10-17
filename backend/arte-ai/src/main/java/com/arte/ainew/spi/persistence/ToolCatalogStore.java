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
     *
     * @param batch 待登记版本集合；工具依赖须已存在或包含在同一批次中。
     * @return 写入、重放或拒绝结果；成功时携带登记批次。
     */
    Mono<StoreOutcome<Batch>> put(Batch batch);

    /**
     * 按固定引用读取工具提供方版本。
     *
     * @param provider 类型为 tool-provider 的固定版本引用。
     * @return 对应提供方版本，不存在时返回空 Mono。
     */
    Mono<ToolProviderDefinition> findProvider(DefinitionRef provider);

    /**
     * 按固定引用读取 Schema 原文及摘要。
     *
     * @param schema 类型为 schema 的固定版本引用。
     * @return 对应 Schema 版本，不存在时返回空 Mono。
     */
    Mono<ToolSchemaDefinition> findSchema(DefinitionRef schema);

    /**
     * 按固定引用读取工具定义及其提供方、执行目标。
     *
     * @param tool 类型为 tool 的固定版本引用。
     * @return 对应工具版本，不存在时返回空 Mono。
     */
    Mono<ToolDefinitionRevision> findTool(DefinitionRef tool);

    /**
     * 分页读取提供方版本；游标不表达版本新旧，管理服务仍须独立过滤当前可见范围。
     *
     * @param afterKey 上一页返回的不透明游标；null 表示从首页开始。
     * @param limit    本页最多返回的条数，范围为 1～100。
     * @return 提供方版本分页及后续游标。
     */
    Mono<Page<ToolProviderDefinition>> listProviders(String afterKey, int limit);

    /**
     * 分页读取某一固定提供方版本下的工具版本；不负责当前可见范围过滤。
     *
     * @param provider 类型为 tool-provider 的固定版本引用，不跨提供方版本查询。
     * @param afterKey 上一页返回的不透明游标；null 表示从首页开始。
     * @param limit    本页最多返回的条数，范围为 1～100。
     * @return 该提供方版本下的工具分页及后续游标。
     */
    Mono<Page<ToolDefinitionRevision>> listTools(DefinitionRef provider, String afterKey, int limit);

    /**
     * 原子登记的版本集合；各列表内引用唯一，三个列表不能同时为空。
     *
     * @param providers 提供方版本的不可变列表，最多 32 项。
     * @param schemas   Schema 版本的不可变列表，最多 128 项。
     * @param tools     工具版本的不可变列表，最多 128 项。
     */
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

    /**
     * 目录查询的一页结果。
     *
     * @param <T>        目录版本记录类型。
     * @param items      本页记录的不可变列表，最多 100 项。
     * @param nextCursor 后续查询的不透明游标；null 表示本次查询未发现更多记录。
     */
    record Page<T>(List<T> items, String nextCursor) {
        public Page {
            items = ContractChecks.list(items, "items", 0, 100);
            if (nextCursor != null) ContractChecks.digest(nextCursor, "nextCursor");
        }
    }
}
