package com.arte.base.model.execution;

import java.net.URI;

/**
 * 可靠受理回执；执行种类和初始状态由所属模块定义，受理不等于生成、保存或应用完成。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record AcceptedExecution(
        String executionId,
        String executionKind,
        String initialState,
        URI statusUri,
        URI eventsUri
) {
}
