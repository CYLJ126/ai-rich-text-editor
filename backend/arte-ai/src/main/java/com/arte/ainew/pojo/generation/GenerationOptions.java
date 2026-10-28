package com.arte.ainew.pojo.generation;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 标准生成选项
 * <p>
 * temperature／topP 为 null 表示使用固定发布配置的默认值，适配器需拒绝不支持的显式参数。
 *
 * @param maxOutputTokens 本次生成的输出 Token 数量上限，范围为 1～1,000,000；还须满足服务端上限及上下文预算的输出预留，不代表实际输出用量或输出字节数。
 * @param temperature     可选的采样温度，范围为 0～2，用于调节生成的随机性；为空时使用默认值，具体参数支持情况由模型及适配器校验。
 * @param topP            可选的核采样累计概率阈值，范围为大于 0 且不超过 1，用于限制参与采样的候选 Token 集合；为空时使用默认值，具体参数支持情况由模型及适配器校验。
 * @param stopSequences   停止序列列表，不能为空，空列表表示不指定停止序列；最多 16 项，不重复，每项非空白且不超过 256 个 UTF-16 单元，保存为不可变列表，由适配器映射为供应商停止参数。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record GenerationOptions(int maxOutputTokens, BigDecimal temperature, BigDecimal topP,
                                List<String> stopSequences) implements Serializable {

    public GenerationOptions {
        ContractChecks.range(maxOutputTokens, "maxOutputTokens", 1, 1_000_000);
        if (temperature != null) {
            temperature = temperature.stripTrailingZeros();
            ContractChecks.require(temperature.precision() <= 38 && temperature.scale() >= -18 && temperature.scale() <= 18,
                    "Temperature exceeds supported precision");
            ContractChecks.require(temperature.signum() >= 0 && temperature.compareTo(BigDecimal.valueOf(2)) <= 0,
                    "Temperature must be between 0 and 2");
        }
        if (topP != null) {
            topP = topP.stripTrailingZeros();
            ContractChecks.require(topP.precision() <= 38 && topP.scale() >= -18 && topP.scale() <= 18,
                    "topP exceeds supported precision");
            ContractChecks.require(topP.signum() > 0 && topP.compareTo(BigDecimal.ONE) <= 0, "topP must be in (0, 1]");
        }
        stopSequences = ContractChecks.list(stopSequences, "stopSequences", 0, 16);
        stopSequences.forEach(stop -> ContractChecks.text(stop, "stop", 256));
        ContractChecks.unique(stopSequences, "stopSequences");
    }
}
