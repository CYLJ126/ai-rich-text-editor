package com.arte.ainew.pojo.execution;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 持久化控制命令回执，ACCEPTED 只代表收到请求；实际终态从 Invocation／Run 权威查询。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record ControlReceipt(String commandId, String executionId, Command command,
                             Outcome outcome, Instant receivedAt) implements Serializable {
    public enum Command {CANCEL, PAUSE, RESUME}

    public enum Outcome {ACCEPTED, ALREADY_TERMINAL, UNSUPPORTED}

    public ControlReceipt {
        ContractChecks.id(commandId, "commandId");
        ContractChecks.id(executionId, "executionId");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
