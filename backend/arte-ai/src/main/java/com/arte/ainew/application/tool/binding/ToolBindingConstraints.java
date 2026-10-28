package com.arte.ainew.application.tool.binding;

import com.arte.ainew.pojo.tool.ToolExecutionPolicy;
import com.arte.core.pojo.value.StructuredValue;

import java.util.TreeMap;

/**
 * 配置完整覆盖层与执行策略交集；不读取历史配置、不授予重试或执行权限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 10:41
 */
public final class ToolBindingConstraints {
    private ToolBindingConstraints() {
    }

    /**
     * 对象递归合并；数组、标量和显式 null 替换。每次只与产品默认配置合并。
     */
    public static StructuredValue.ObjectValue merge(StructuredValue.ObjectValue defaults, StructuredValue.ObjectValue overrides) {
        var fields = new TreeMap<>(defaults.fields());
        overrides.fields().forEach((key, value) -> fields.merge(key, value, (oldValue, newValue) ->
                oldValue instanceof StructuredValue.ObjectValue a && newValue instanceof StructuredValue.ObjectValue b
                        ? merge(a, b) : newValue));
        return new StructuredValue.ObjectValue(fields);
    }

    /**
     * 上限取小、退避取大、审批取强；冲突交给调用者拒绝，不能通过放宽期限解决。
     */
    public static ToolExecutionPolicy intersect(ToolExecutionPolicy a, ToolExecutionPolicy b) {
        if (b == null) return a;
        return new ToolExecutionPolicy(a.timeout().compareTo(b.timeout()) <= 0 ? a.timeout() : b.timeout(),
                Math.min(a.maxAttempts(), b.maxAttempts()),
                a.retryBackoff().compareTo(b.retryBackoff()) >= 0 ? a.retryBackoff() : b.retryBackoff(),
                Math.min(a.maxInputBytes(), b.maxInputBytes()), Math.min(a.maxOutputBytes(), b.maxOutputBytes()),
                a.approvalMode().satisfies(b.approvalMode()) ? a.approvalMode() : b.approvalMode());
    }
}
