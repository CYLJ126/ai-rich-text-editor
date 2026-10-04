package com.arte.ainew.pojo.budget;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * 非负金额与币种，不使用浮点数。标准化表示方便防重对账；计价精度及舍入由固定费率版本定义。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record Money(BigDecimal amount, Currency currency) implements Serializable {
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        amount = amount.stripTrailingZeros();
        ContractChecks.require(amount.signum() >= 0 && amount.precision() <= 38
                && amount.scale() >= -18 && amount.scale() <= 18, "Amount exceeds supported precision or is negative");
    }
}
