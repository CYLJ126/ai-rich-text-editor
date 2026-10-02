package com.arte.ai.model.generation;

/**
 * 基础生成选项；供应商仍须校验自身支持范围。
 */
public record ModelOptions(Double temperature, Integer maxOutputTokens) {
    public ModelOptions {
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2))
            throw new IllegalArgumentException("temperature must be finite and between 0 and 2");
        if (maxOutputTokens != null && maxOutputTokens <= 0)
            throw new IllegalArgumentException("maxOutputTokens must be positive");
    }
}
