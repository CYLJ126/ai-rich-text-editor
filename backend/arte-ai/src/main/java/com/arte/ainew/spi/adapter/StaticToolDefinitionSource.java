package com.arte.ainew.spi.adapter;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.tool.ToolDefinitionRevision;
import com.arte.ainew.pojo.tool.ToolSchemaDefinition;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 部署代码提供的静态工具定义来源
 * <p>
 * 显式登记 LOCAL／HTTP／MCP 工具元数据；一次返回完整集合，不返回增量或自动发布工具。
 * 只读取受信代码内的元数据，不调用工具、扫描任意 Bean 或访问外部服务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:30
 */
@FunctionalInterface
public interface StaticToolDefinitionSource {

    /**
     * 加载本提供方的完整定义；空集合须显式返回 Definitions，空 Mono 表示来源异常。
     *
     * @return 固定 Schema 和工具版本集合；失败通过 onError 传播。
     */
    Mono<Definitions> discover();

    /**
     * 一次完整发现的不可变原文；提供方与执行器由受信注册表另外指定。
     *
     * @param schemas 本次目录需要的 Schema，最多 128 项，引用不能重复。
     * @param tools   该提供方本次提供的工具，最多 128 项，工具引用不能重复。
     */
    record Definitions(List<ToolSchemaDefinition> schemas, List<ToolDefinitionRevision> tools) {

        public Definitions {
            schemas = ContractChecks.list(schemas, "schemas", 0, 128);
            tools = ContractChecks.list(tools, "tools", 0, 128);
            ContractChecks.unique(schemas.stream().map(ToolSchemaDefinition::definition).toList(), "schema references");
            ContractChecks.unique(tools.stream().map(t -> t.tool().definition()).toList(), "tool references");
        }
    }
}
