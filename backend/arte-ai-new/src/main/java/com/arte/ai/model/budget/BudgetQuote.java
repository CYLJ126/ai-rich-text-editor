package com.arte.ai.model.budget;

import com.arte.base.validation.ContractChecks;

import java.math.BigDecimal;

/**
 * 运维配置的单次最大费用预留；不是供应商最终账单。
 */
public record BudgetQuote(BigDecimal maximumAmount, String currency) {
    public BudgetQuote {
        maximumAmount = ContractChecks.required(maximumAmount, "maximumAmount");
        currency = ContractChecks.identifier(currency, "currency");
        if (maximumAmount.signum() <= 0 || maximumAmount.scale() > 8 || maximumAmount.precision() > 18)
            throw new IllegalArgumentException("maximumAmount must fit a positive fixed-point amount");
    }
}
