package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.budget.Money;

import java.io.Serializable;
import java.util.Objects;

/**
 * 固定版本的按次报价上界；零单价仍为 CHARGE，不表达模型 Token 或未经约束的可变收费。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 14:00 ✾
 */
public record ToolPrice(DefinitionRef rate, Money perCall) implements Serializable {

    public ToolPrice {
        Objects.requireNonNull(rate).requireType("rate");
        Objects.requireNonNull(perCall);
    }
}
