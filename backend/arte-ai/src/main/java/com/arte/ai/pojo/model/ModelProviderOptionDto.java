package com.arte.ai.pojo.model;

/**
 * 模型提供商下拉选项。
 */
public record ModelProviderOptionDto(
        String value,
        String label,
        boolean disabled,
        String defaultApiBaseUrl) {
}
