package com.arte.ai.spi.adapter;

import com.arte.ai.model.definition.ConnectionDefinition;
import com.arte.base.execution.ExecutionCheckpoint;

/**
 * 私有运行端口；凭据按 SecretRef 在发送时解析，响应正文必须有界。
 */
public interface ConnectionRuntime {
    byte[] exchange(ConnectionDefinition connection, byte[] body, ExecutionCheckpoint checkpoint) throws Exception;

    default void exchangeStream(ConnectionDefinition connection, byte[] body, ExecutionCheckpoint checkpoint, ChunkConsumer consumer) throws Exception {
        throw new UnsupportedOperationException("streaming connection unavailable");
    }

    @FunctionalInterface
    interface ChunkConsumer {
        void accept(byte[] bytes, int offset, int length) throws Exception;
    }
}
