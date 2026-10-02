package com.arte.ai.spi.store;

import com.arte.ai.model.execution.ModelEvent;
import com.arte.base.model.execution.ExecutionEvent;
import com.arte.base.model.identity.ExecutionScope;

import java.util.List;

/**
 * 以 exclusive 游标读取耐久事件，查询不得重新执行模型。终态与结果由执行存储原子写入。
 */
public interface ExecutionEventStore {
    List<ExecutionEvent<ModelEvent>> read(ExecutionScope scope, String executionId, long afterSequence, int limit);
}
