package com.arte.ai.model.remote;

import com.arte.ai.model.budget.Usage;
import com.arte.ai.model.tool.StructuredValue;

/**
 * 远程应用结果、会话和任务引用；保留远端生命周期及可观测边界。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record RemoteApplicationResult(
        StructuredValue output,
        RemoteSessionRef session,
        RemoteTaskRef task,
        Usage usage
) {
}
