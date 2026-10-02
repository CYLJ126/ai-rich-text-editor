package com.arte.ai.model.budget;

import java.math.BigDecimal;

/**
 * 供应商报告的用量；缺失值表示未知，不能记为零或视作最终费用。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record Usage(
        Long inputTokens,
        Long outputTokens,
        BigDecimal reportedCost,
        String currency
) {
}
