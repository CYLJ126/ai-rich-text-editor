package com.arte.ainew.common.execution;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.util.Objects;
import java.util.Set;

/**
 * 远端任务另有身份、连接、归属及生命周期；持有远端标识不获得访问权，取消能力不承诺一定停止。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record RemoteTaskRef(String taskId, DefinitionRef connection, ExecutionOwner owner,
                            State state, Set<Control> controls) implements Serializable {
    public enum State {
        QUEUED, RUNNING, WAITING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN;

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }
    }

    public enum Control {QUERY, CANCEL, CONTINUE}

    public RemoteTaskRef {
        ContractChecks.id(taskId, "taskId");
        Objects.requireNonNull(connection, "connection").requireType("connection");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(state, "state");
        controls = ContractChecks.set(controls, "controls", Control.values().length);
    }
}
