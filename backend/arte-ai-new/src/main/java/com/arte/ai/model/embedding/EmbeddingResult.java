package com.arte.ai.model.embedding;

import com.arte.ai.model.budget.Usage;

import java.util.List;

/**
 * 向量、维度及模型版本；不同向量空间不得混用。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record EmbeddingResult(
        List<List<Float>> vectors,
        int dimensions,
        String modelVersion,
        Usage usage
) {
}
