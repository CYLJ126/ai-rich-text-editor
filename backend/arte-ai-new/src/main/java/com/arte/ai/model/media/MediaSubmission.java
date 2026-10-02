package com.arte.ai.model.media;

/**
 * 媒体提交的两种结果：已完成产物或已受理远端任务；不以空结果区分同步与异步。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public sealed interface MediaSubmission permits MediaResult, MediaTask {
}
