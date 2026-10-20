package com.arte.ainew.application.tool.outcome;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.ainew.spi.adapter.ToolOutcomeQueryAdapter;

import java.util.List;
import java.util.Optional;

/**
 * 当前部署对历史固定契约明确登记的收尾查询；不改变已发布执行契约或按声明自动启用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 10:48 ✾
 */
public final class ToolOutcomeQueryRegistry {

    private final List<ToolOutcomeQueryAdapter> adapters;

    public ToolOutcomeQueryRegistry(List<ToolOutcomeQueryAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
        ContractChecks.unique(this.adapters.stream().map(ToolOutcomeQueryAdapter::tool).toList(), "outcome query contracts");
    }

    public Optional<ToolOutcomeQueryAdapter> find(ToolExecutionSnapshot snapshot) {
        return adapters.stream().filter(a -> a.tool().equals(snapshot.request().input().tool()) && a.supports(snapshot)).findFirst();
    }
}
