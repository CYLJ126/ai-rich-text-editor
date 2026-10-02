package com.arte.ai.model.message;

import com.arte.base.model.artifact.ArtifactRef;

/**
 * 图片、音频、视频等产物内容部件；可发送模态由能力契约校验。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ArtifactPart(
        ArtifactRef artifact
) implements ContentPart {
}
