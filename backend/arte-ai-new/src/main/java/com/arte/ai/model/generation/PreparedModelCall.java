package com.arte.ai.model.generation;

import com.arte.base.execution.ExecutionCheckpoint;
import com.arte.base.validation.ContractChecks;

/**
 * 内部准备结果；摘要覆盖实际发送的完整协议正文。operation 不允许暴露给客户端或序列化。
 */
public record PreparedModelCall(ModelPlan plan, String contentDigest, int inputBytes, Operation operation,
                                StreamingOperation streamingOperation) {
    public PreparedModelCall(ModelPlan plan, String contentDigest, int inputBytes, Operation operation) {
        this(plan, contentDigest, inputBytes, operation, null);
    }
    public PreparedModelCall {
        plan = ContractChecks.required(plan, "plan");
        contentDigest = ContractChecks.identifier(contentDigest, "contentDigest");
        operation = ContractChecks.required(operation, "operation");
        if (inputBytes <= 0) throw new IllegalArgumentException("inputBytes must be positive");
    }

    @FunctionalInterface
    public interface Operation {
        ModelResult invoke(ExecutionCheckpoint checkpoint) throws Exception;
    }

    @FunctionalInterface
    public interface StreamingOperation {
        ModelResult invoke(ExecutionCheckpoint checkpoint, java.util.function.Consumer<String> deltas) throws Exception;
    }
}
