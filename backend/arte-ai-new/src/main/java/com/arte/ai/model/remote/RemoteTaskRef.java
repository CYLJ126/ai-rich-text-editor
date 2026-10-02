package com.arte.ai.model.remote;

import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.model.execution.ControlAction;
import com.arte.base.model.identity.ExecutionScope;

import java.util.Set;

/**
 * 连接内远端任务身份、所属范围及声明的控制能力；取消请求与实际终态分开。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record RemoteTaskRef(
        DefinitionRef connectionRef,
        ExecutionScope scope,
        String remoteTaskId,
        RemoteTaskStatus status,
        Set<ControlAction> supportedControls
) {
}
