package com.arte.ainew.pojo.control;

import com.arte.ainew.common.reference.DefinitionRef;

import java.util.List;

/**
 * 当前主体可选择的固定版本文本聊天配置；只包含公开选择字段，不携带连接、凭据或授权定义。
 * 发现结果不授予执行权限，也不保证凭据、预算余额或供应商当前可用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 05:52 ✾
 */
public record ChatConfigurationOptions(List<ModelOption> options) {
    public ChatConfigurationOptions {
        options = List.copyOf(options);
    }

    public record ModelOption(String displayName, DefinitionRef binding, DefinitionRef capability,
                              long contextWindowTokens, Limits limits, Defaults defaults, List<String> budgetRefs) {
        public ModelOption {
            budgetRefs = List.copyOf(budgetRefs);
        }
    }

    /**
     * maxInputTokens 是单字段上限；实际输入与输出之和仍须不超过 contextWindowTokens。
     */
    public record Limits(long maxInputTokens, int maxOutputTokens, int maxInputBytes,
                         long maxOutputBytes, long maxTimeoutSeconds) {
    }

    /**
     * 推荐额度仅用于表单初始化，不是模型参数默认值或可支付承诺。
     */
    public record Defaults(long maxInputTokens, int maxOutputTokens, long timeoutSeconds) {
    }
}
