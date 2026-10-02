package com.arte.ai.model.execution;

import java.time.Duration;

/**
 * 通用单次执行选项；能力专有参数保留在各自请求中。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ExecutionOptions(
        Duration timeout,
        boolean streaming
) {
}
