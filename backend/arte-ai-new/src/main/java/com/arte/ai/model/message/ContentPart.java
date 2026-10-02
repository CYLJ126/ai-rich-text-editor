package com.arte.ai.model.message;

/**
 * 消息内容的已知变体；文本与产物引用分别表达，新增模态时显式扩展。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public sealed interface ContentPart permits TextPart, ArtifactPart {
}
