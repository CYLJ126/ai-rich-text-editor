package com.arte.base.spi.execution;

import com.arte.base.execution.ExecutionCheckpoint;

/**
 * 阻塞工作可使用专用有界执行器；实现应在后续敏感操作前检查停止并重新授权。
 */
@FunctionalInterface
public interface ExecutionTask<T> {
    T execute(ExecutionCheckpoint checkpoint) throws Exception;
}
