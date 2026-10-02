package com.arte.ai.api.gateway;

import com.arte.ai.model.generation.GenerationRequest;
import com.arte.ai.model.generation.ModelPlan;
import com.arte.ai.model.generation.ModelResult;
import com.arte.ai.model.generation.PreparedModelCall;

/**
 * 类型化生成网关；prepare 不发送网络请求，不执行模型提出的业务工具。
 */
public interface ModelGateway {
    PreparedModelCall prepare(ModelPlan plan, GenerationRequest request);

    default ModelResult generate(PreparedModelCall prepared, com.arte.base.execution.ExecutionCheckpoint checkpoint) throws Exception {
        return prepared.operation().invoke(checkpoint);
    }
}
