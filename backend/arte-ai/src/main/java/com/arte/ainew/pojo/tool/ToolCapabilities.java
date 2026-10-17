package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.CapabilityDescriptor;

import java.io.Serializable;
import java.util.Set;

/**
 * 工具适配器声明的能力，不包含平台本身的排队、租约和恢复能力。
 * 发布时仍须核实真实实现，声明不授予执行权限。
 *
 * @param features              工具适用的通用能力；远端受理任务必须可查询，才能在重启后继续跟踪。
 * @param outcomeQuerySupported 能否按原操作身份核对业务结果，与远端任务查询、费用核对分别表达。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17
 */
public record ToolCapabilities(Set<CapabilityDescriptor.Feature> features,
                               boolean outcomeQuerySupported) implements Serializable {

    private static final Set<CapabilityDescriptor.Feature> TOOL_FEATURES = Set.of(
            CapabilityDescriptor.Feature.STREAMING, CapabilityDescriptor.Feature.ASYNC_TASK,
            CapabilityDescriptor.Feature.TASK_QUERY, CapabilityDescriptor.Feature.CANCEL,
            CapabilityDescriptor.Feature.USAGE_REPORTING);

    public ToolCapabilities {
        features = ContractChecks.set(features, "features", TOOL_FEATURES.size());
        ContractChecks.require(TOOL_FEATURES.containsAll(features), "Unsupported tool capability feature");
        ContractChecks.require(!features.contains(CapabilityDescriptor.Feature.ASYNC_TASK)
                        || features.contains(CapabilityDescriptor.Feature.TASK_QUERY),
                "Asynchronous tool tasks require task query support");
    }
}
