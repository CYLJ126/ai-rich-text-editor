package com.arte.base.execution;

import com.arte.base.model.execution.CancellationStatus;
import com.arte.base.model.execution.ExecutionContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * 返回本地工作句柄而不是 AcceptedExecution；调用方不能通过修改 future 提前释放工作资源。
 */
public final class TaskHandle<T> {
    private final String taskId;
    private final ExecutionContext context;
    private final CompletableFuture<T> result;
    private final Supplier<TaskState> state;
    private final Supplier<CancellationStatus> cancel;

    TaskHandle(String taskId, ExecutionContext context, CompletableFuture<T> result,
               Supplier<TaskState> state, Supplier<CancellationStatus> cancel) {
        this.taskId = taskId;
        this.context = context;
        this.result = result;
        this.state = state;
        this.cancel = cancel;
    }

    public String taskId() {
        return taskId;
    }

    public ExecutionContext context() {
        return context;
    }

    public TaskState state() {
        return state.get();
    }

    public CompletionStage<T> completion() {
        return result.minimalCompletionStage();
    }

    public CancellationStatus requestCancellation() {
        return cancel.get();
    }
}
