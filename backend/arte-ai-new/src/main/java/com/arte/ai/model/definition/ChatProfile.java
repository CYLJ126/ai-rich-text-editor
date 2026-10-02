package com.arte.ai.model.definition;

import com.arte.base.model.resource.SourceRef;

import java.util.List;

/**
 * 可复用聊天配置；默认资料仍需逐次授权。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ChatProfile(
        DefinitionRef ref,
        DefinitionRef modelBinding,
        List<SourceRef> defaultSources,
        List<DefinitionRef> allowedCapabilities,
        DefinitionRef defaultAction
) {
}
