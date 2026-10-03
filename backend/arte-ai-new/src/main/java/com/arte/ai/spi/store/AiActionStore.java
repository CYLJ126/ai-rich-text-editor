package com.arte.ai.spi.store;

import com.arte.ai.model.action.AiActionExecution;
import com.arte.base.model.identity.ExecutionScope;

import java.util.Optional;

/**
 * 不可变动作输入存储；同一主体范围和提交键唯一，同键不同摘要必须报幂等冲突。
 */
public interface AiActionStore {
    AiActionExecution claim(AiActionExecution draft);

    Optional<AiActionExecution> find(ExecutionScope scope, String id);

    Optional<AiActionExecution> findIdempotent(ExecutionScope scope, String key);
}
