package com.arte.ai.pojo.model;

/**
 * 模型提供商返回的模型及其可维护能力。
 */
public record AvailableModelDto(
        String modelId,
        String modelName,
        Boolean supportVision,
        Boolean supportFunction,
        Boolean supportThinking,
        Boolean supportSearch,
        Integer contextWindow,
        Integer maxTokens) {
}
