package com.arte.ainew.application.execution;

import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.execution.Usage;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 固定费率计算；上下文预留按容量上界，最终费用只接受完整供应商报告，估算不冒充账单。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 16:14 ✾
 */
final class GenerationPricing {

    private GenerationPricing() {
    }

    static Money amount(NewAiProperties.Rate rate, long input, long output) {
        var cost = rate.inputPerMillion().amount().multiply(BigDecimal.valueOf(input))
                .add(rate.outputPerMillion().amount().multiply(BigDecimal.valueOf(output)))
                .divide(BigDecimal.valueOf(1_000_000), 18, RoundingMode.HALF_UP);
        return new Money(cost, rate.inputPerMillion().currency());
    }

    static boolean known(Usage usage) {
        return usage.basis() == Usage.Basis.PROVIDER_REPORTED
                && usage.inputTokens() != null && usage.outputTokens() != null;
    }
}
