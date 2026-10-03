package com.arte.ai.model.execution;

import com.arte.ai.model.generation.ModelResult;
import com.arte.base.model.execution.ExecutionError;

/**
 * 耐久事件负载，非流式调用只发布受理、运行及终态；正文只存在结果／输出存储。
 */
public record ModelEvent(ExecutionStatus status, ModelResult result, ExecutionError error, String textDelta) {
    public ModelEvent(ExecutionStatus status, ModelResult result, ExecutionError error) {
        this(status, result, error, null);
    }
}
