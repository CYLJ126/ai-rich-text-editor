package com.arte.ai.model.remote;

import com.arte.ai.model.definition.DefinitionRef;
import com.arte.base.model.identity.ExecutionScope;

/**
 * 按连接、租户及主体隔离的远端会话引用。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record RemoteSessionRef(
        DefinitionRef connectionRef,
        ExecutionScope scope,
        String remoteSessionId
) {
}
