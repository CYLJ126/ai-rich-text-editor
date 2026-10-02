package com.arte.ai.model.media;

import com.arte.ai.model.remote.RemoteTaskRef;

/**
 * 已受理的异步媒体任务，保留远端身份与控制能力。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record MediaTask(
        RemoteTaskRef task
) implements MediaSubmission {
}
