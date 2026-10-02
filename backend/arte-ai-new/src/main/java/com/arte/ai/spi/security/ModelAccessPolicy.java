package com.arte.ai.spi.security;

import com.arte.ai.model.generation.ModelPlan;
import com.arte.base.model.execution.ExecutionContext;

/**
 * 主体、应用和任务的当前模型使用权限；调用前及输出／查询边界均重新核对。
 */
@FunctionalInterface
public interface ModelAccessPolicy {
    void requireAllowed(ExecutionContext context, ModelPlan plan);
}
