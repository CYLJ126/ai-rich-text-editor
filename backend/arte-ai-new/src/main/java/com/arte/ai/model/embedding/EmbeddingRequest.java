package com.arte.ai.model.embedding;

import java.util.List;

/**
 * 批量向量输入；向量模型由外层能力与绑定确定。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record EmbeddingRequest(
        List<String> inputs
) {
}
