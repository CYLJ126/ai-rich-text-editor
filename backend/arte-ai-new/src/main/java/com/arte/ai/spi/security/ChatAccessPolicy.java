package com.arte.ai.spi.security;

import com.arte.ai.model.definition.DefinitionRef;
import com.arte.base.model.execution.ExecutionContext;

/**
 * 当前身份、成员及应用／绑定的聊天许可；数据查询另按完整主体作用域隔离。
 */
@FunctionalInterface
public interface ChatAccessPolicy {
    void requireAllowed(ExecutionContext viewer, DefinitionRef binding);
}
