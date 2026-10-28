package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.core.pojo.platform.ExecutionOwner;

import java.util.Objects;

/**
 * 受信查询适配器的只读证据，不接受浏览器或模型自报结论。查无记录不能证明未执行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 10:48 ✾
 */
public record ToolOutcomeEvidence(ExecutionOwner owner, String invocationId, String attemptId, String operationKey,
                                  Decision decision, String reference, String digest, ToolResult result) {

    public enum Decision {CONFIRMED_SUCCEEDED, CONFIRMED_FAILED, PROVEN_NOT_EXECUTED, STILL_UNKNOWN}

    public ToolOutcomeEvidence {
        Objects.requireNonNull(owner);
        ContractChecks.id(invocationId, "invocationId");
        ContractChecks.id(attemptId, "attemptId");
        ContractChecks.id(operationKey, "operationKey");
        Objects.requireNonNull(decision);
        if (decision != Decision.STILL_UNKNOWN) {
            ContractChecks.id(reference, "evidence reference");
            ContractChecks.digest(digest, "evidence digest");
            Objects.requireNonNull(result);
        } else
            ContractChecks.require(reference == null && digest == null && result == null, "Unknown is not a confirmed result");
    }

    public static ToolOutcomeEvidence unknown(ToolOutcomeCheck.Claim claim) {
        return new ToolOutcomeEvidence(claim.owner(), claim.work().invocationId(), claim.work().attemptId(), claim.work().operationKey(), Decision.STILL_UNKNOWN, null, null, null);
    }
}
