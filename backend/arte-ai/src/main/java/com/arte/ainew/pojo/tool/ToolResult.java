package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.reference.ArtifactRef;
import com.arte.ainew.common.reference.SourceRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.common.value.StructuredValue;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * 工具调用结果
 * <p>
 * 结构化结果、来源、产物及副作用事实；失败仍可能已生效，UNKNOWN 禁止作为安全重试的证据。
 * 由受信适配器或恢复流程提供，协调层仍须校验结果、保存字节并提交执行状态及费用事实。
 *
 * @param callId     对应 ToolInvocation 的调用关联标识，不能为空白且不超过 256 个 UTF-16 单元，必须与原输入一致，用于关联模型工具请求与响应。
 * @param outcome    本次工具执行的业务结果，不能为空，区分已成功、已失败和结果未知；不代替 Invocation 终态、结果持久化或费用结算状态。
 * @param output     可选的结构化输出，须通过固定输出 Schema 及大小校验；SUCCEEDED 时必须提供 output 或至少一个 artifact，不能只有来源引用。
 * @param sources    结果关联的来源引用列表，不能为空，最多 ContractChecks.MAX_ITEMS 项且 citationId 不重复，保存为不可变列表；无来源时使用空列表，展示及重用仍须授权。
 * @param artifacts  结果关联的本系统产物引用列表，不能为空，最多 ContractChecks.MAX_PARTS 项，保存为不可变列表；无产物时使用空列表，引用不代替产物读取授权。
 * @param sideEffect 本次实际副作用事实，不能为空，区分未发生、可能发生及已确认发生；有 error 时必须与 error.sideEffect 一致，不等同于工具定义声明的风险类别。
 * @param error      可持久化的安全错误事实，SUCCEEDED 时必须为空，FAILED 或 UNKNOWN 时必填；UNKNOWN 须对应未知结果确定性，FAILED 须对应已知结果确定性。
 * @param charge     可选的受信已确认费用及证据，缺失不能推导为已知零费用；实际接收条件由工具计费策略和执行链路校验，不表示账本已完成结算。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:20 ✾
 */
public record ToolResult(String callId, Outcome outcome, StructuredValue output, List<SourceRef> sources,
                         List<ArtifactRef> artifacts, ExecutionError.SideEffect sideEffect, ExecutionError error,
                         @JsonInclude(JsonInclude.Include.NON_NULL) ToolCharge charge) implements Serializable {

    public ToolResult(String callId, Outcome outcome, StructuredValue output, List<SourceRef> sources, List<ArtifactRef> artifacts,
                      ExecutionError.SideEffect sideEffect, ExecutionError error) {
        this(callId, outcome, output, sources, artifacts, sideEffect, error, null);
    }

    /**
     * 工具业务结果的确定状态，与平台执行状态及费用状态分别记录，不能仅凭失败判断没有副作用或费用。
     */
    public enum Outcome {
        // 工具已返回成功结果，须有结构化输出或产物且没有错误；仍需校验及耐久提交。
        SUCCEEDED,
        // 工具已知失败，须提供已知结果的错误事实；不自动表示没有副作用或可以安全重试。
        FAILED,
        // 尚无法确认实际执行结果，须保留未知错误及副作用事实，进入核对流程，不能当作未执行而直接重发。
        UNKNOWN
    }

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
