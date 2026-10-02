package com.arte.ai.model.media;

import com.arte.ai.model.message.ContentPart;

import java.util.List;

/**
 * 媒体生成输入；可接受模态及格式由媒体能力契约声明。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record MediaRequest(
        String instruction,
        List<ContentPart> inputs,
        String outputMediaType
) {
}
