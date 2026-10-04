package com.arte.ainew.pojo.generation;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 标准生成选项
 * <p>
 * null 表示使用固定发布配置的默认值，适配器需拒绝不支持的显式参数。
 *
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
