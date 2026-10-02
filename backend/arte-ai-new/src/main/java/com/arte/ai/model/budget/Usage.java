package com.arte.ai.model.budget;

import com.arte.base.validation.ContractChecks;

import java.math.BigDecimal;

/**
 * 缺失值表示未知；reportedCost 是受控提供者的费用报告／估算，不能默认为零。
 */
public record Usage(Long inputTokens, Long outputTokens, BigDecimal reportedCost, String currency) {
    public Usage {
        if (inputTokens != null && inputTokens < 0 || outputTokens != null && outputTokens < 0
                || reportedCost != null && reportedCost.signum() < 0)
            throw new IllegalArgumentException("usage must be nonnegative");
        currency = ContractChecks.optionalIdentifier(currency, "currency");
        if (reportedCost != null && currency == null) throw new IllegalArgumentException("cost requires currency");
    }

    public static Usage unknown() {
        return new Usage(null, null, null, null);
    }
}
