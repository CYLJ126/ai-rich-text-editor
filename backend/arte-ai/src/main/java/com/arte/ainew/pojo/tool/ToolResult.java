package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.reference.ArtifactRef;
import com.arte.ainew.common.reference.SourceRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.common.value.StructuredValue;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * 工具调用结果
 * 结构化结果、来源、产物及副作用事实；失败仍可能已生效，UNKNOWN 禁止作为安全重试的证据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:20 ✾
 */
public record ToolResult(String callId, Outcome outcome, StructuredValue output, List<SourceRef> sources,
                         List<ArtifactRef> artifacts, ExecutionError.SideEffect sideEffect,
                         ExecutionError error) implements Serializable {
    public enum Outcome {SUCCEEDED, FAILED, UNKNOWN}

    public ToolResult {
        ContractChecks.id(callId, "callId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(sideEffect, "sideEffect");
        sources = ContractChecks.list(sources, "sources", 0, ContractChecks.MAX_ITEMS);
        ContractChecks.unique(sources.stream().map(SourceRef::citationId).toList(), "source IDs");
        artifacts = ContractChecks.list(artifacts, "artifacts", 0, ContractChecks.MAX_PARTS);
        ContractChecks.require(outcome != Outcome.SUCCEEDED || error == null && (output != null || !artifacts.isEmpty()),
                "Success requires output or artifacts and no error");
        ContractChecks.require(outcome == Outcome.SUCCEEDED || error != null, "Unsuccessful tool requires error facts");
        if (error != null) {
            ContractChecks.require(error.sideEffect() == sideEffect, "Tool side-effect facts disagree");
        }
        ContractChecks.require(outcome != Outcome.UNKNOWN || error.certainty() == ExecutionError.Certainty.UNKNOWN,
                "Unknown tool outcome requires unknown certainty");
        ContractChecks.require(error == null || (outcome == Outcome.UNKNOWN)
                == (error.certainty() == ExecutionError.Certainty.UNKNOWN), "Unknown certainty requires UNKNOWN tool outcome");
    }
}
