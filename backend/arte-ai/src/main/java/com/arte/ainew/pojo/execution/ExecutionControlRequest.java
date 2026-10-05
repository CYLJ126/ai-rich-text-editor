package com.arte.ainew.pojo.execution;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.util.Objects;

/**
 * 控制命令；commandId 为服务端分配，commandKey 在主体＋目标＋命令作用域防重。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 13:09 ✾
 */
public record ExecutionControlRequest(String commandId, String invocationId, String commandKey,
                                      ControlReceipt.Command command) implements Serializable {
    public ExecutionControlRequest {
        ContractChecks.id(commandId, "commandId");
        ContractChecks.id(invocationId, "invocationId");
        ContractChecks.id(commandKey, "commandKey");
        Objects.requireNonNull(command, "command");
    }
}
