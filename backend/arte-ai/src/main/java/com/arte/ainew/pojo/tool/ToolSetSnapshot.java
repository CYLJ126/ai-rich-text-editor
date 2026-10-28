package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.core.pojo.platform.ExecutionOwner;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.List;
import java.util.Objects;

/**
 * 本轮受信工具材料；不作为 HTTP DTO，不包含 Callback，不因快照存在而授予执行权限。
 * 顺序决定供应商别名；固定后不能重新排序、跟随发布或按全局名称补齐工具。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 15:20 ✾
 */
public record ToolSetSnapshot(ExecutionOwner owner, List<Entry> entries) {

    public record Entry(String modelName, ToolBindingResolution binding, ToolExecutionPolicy policy,
                        ToolSchemaDefinition inputSchema) {
        public Entry {
            ContractChecks.require(modelName != null && modelName.matches("tool_[0-9]{1,3}"), "Invalid model tool name");
            Objects.requireNonNull(binding);
            Objects.requireNonNull(policy);
            Objects.requireNonNull(inputSchema);
            ContractChecks.require(binding.tool().tool().inputSchema().equals(inputSchema.definition()), "Tool schema mismatch");
        }

        public GenerationRequest.ToolDefinition description() {
            var tool = binding.tool().tool();
            return new GenerationRequest.ToolDefinition(tool.definition(), tool.description(), tool.inputSchema(), tool.risk().sideEffect());
        }
    }

    public ToolSetSnapshot {
        Objects.requireNonNull(owner);
        entries = ContractChecks.list(entries, "tool set", 1, ContractChecks.MAX_TOOLS);
        ContractChecks.unique(entries.stream().map(Entry::modelName).toList(), "model names");
        ContractChecks.unique(entries.stream().map(e -> e.binding().definition().id()).toList(), "binding IDs");
        ContractChecks.unique(entries.stream().map(e -> e.binding().tool().tool().definition().id()).toList(), "tool IDs");
        for (int i = 0; i < entries.size(); i++) {
            ContractChecks.require(entries.get(i).modelName().equals("tool_" + i), "Tool set order changed");
            ContractChecks.require(entries.get(i).binding().owner().equals(owner), "Tool set owner mismatch");
        }
    }

    public List<GenerationRequest.ToolDefinition> descriptions() {
        return entries.stream().map(Entry::description).toList();
    }

    public Entry named(String name) {
        return entries.stream().filter(entry -> entry.modelName().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown model tool name"));
    }

    public Entry tool(DefinitionRef definition) {
        return entries.stream().filter(entry -> entry.binding().tool().tool().definition().equals(definition)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tool is outside the fixed set"));
    }
}
