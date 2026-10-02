package com.arte.ai.model.remote;

import com.arte.ai.model.tool.StructuredValue;

/**
 * 远程应用输入及可选的继续会话引用，不复用模型生成请求。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record RemoteApplicationRequest(
        StructuredValue input,
        RemoteSessionRef session
) {
}
