package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;

import java.util.Objects;

/**
 * 可信应用命令；owner 为原调用归属，当前决定者另由宿主身份提供，不能来自正文。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 00:05 ✾
 */
public record ToolApprovalCommand(ExecutionOwner owner, String invocationId, String approvalId,
                                  long expectedVersion, String subjectDigest, Decision decision, String commandKey) {

    public enum Decision {APPROVE, REJECT}

    public ToolApprovalCommand {
        Objects.requireNonNull(owner);
        ContractChecks.id(invocationId, "invocationId");
        ContractChecks.id(approvalId, "approvalId");
        ContractChecks.range(expectedVersion, "expectedVersion", 0, Long.MAX_VALUE - 1);
        ContractChecks.digest(subjectDigest, "subjectDigest");
        Objects.requireNonNull(decision);
        ContractChecks.id(commandKey, "commandKey");
    }
}
