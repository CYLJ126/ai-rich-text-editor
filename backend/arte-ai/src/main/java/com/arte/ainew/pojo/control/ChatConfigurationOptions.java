package com.arte.ainew.pojo.control;

import com.arte.core.pojo.reference.DefinitionRef;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 当前主体可选择的固定版本文本聊天配置
 * <p>
 * 只包含公开选择字段，不携带连接、凭据或授权定义。
 * 发现结果不授予执行权限，也不保证凭据、预算余额或供应商当前可用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 05:52 ✾
 */
public record ChatConfigurationOptions(List<ModelOption> options) {

    public ChatConfigurationOptions {
        options = List.copyOf(options);
    }

    /**
     * 当前可执行模型的公开选项
     * <p>
     * 管理定位元数据不包含内部授权证明或密钥。
     *
     * @param displayName         当前模型展示名称，兼容目录使用远端模型名
     * @param binding             固定模型绑定
     * @param capability          固定能力引用
     * @param contextWindowTokens 上下文容量
     * @param limits              本次适用上限
     * @param defaults            表单初始建议值
     * @param budgetRefs          当前主体获准使用的兼容预算
     * @param toolCallsSupported  是否具备当前工具聊天适配能力
     * @param resourceId          公共模型稳定管理 ID，兼容旧 Grant 目录为空
     * @param source              公共模型定义来源 FIXED／DATABASE／PERSONAL；兼容旧 Grant 目录为空，不代表供应商
     */
    public record ModelOption(String displayName, DefinitionRef binding, DefinitionRef capability,
                              long contextWindowTokens, Limits limits, Defaults defaults, List<String> budgetRefs,
                              boolean toolCallsSupported, @JsonInclude(JsonInclude.Include.NON_NULL) String resourceId,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String source) {

        public ModelOption(String displayName, DefinitionRef binding, DefinitionRef capability,
                           long contextWindowTokens, Limits limits, Defaults defaults, List<String> budgetRefs, boolean toolCallsSupported) {
            this(displayName, binding, capability, contextWindowTokens, limits, defaults, budgetRefs, toolCallsSupported, null, null);
        }

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
