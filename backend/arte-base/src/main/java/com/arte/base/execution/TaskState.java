package com.arte.base.execution;

/**
 * Java 工作的本地生命周期；终止不证明远端副作用或费用已撤销。
 */
public enum TaskState {QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT}
