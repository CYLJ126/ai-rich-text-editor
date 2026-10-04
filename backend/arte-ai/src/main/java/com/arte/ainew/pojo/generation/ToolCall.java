package com.arte.ainew.pojo.generation;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.common.value.StructuredValue;

import java.io.Serializable;
import java.util.Objects;

/**
 * 模型提出的完整工具调用；tool 从本次允许工具中解析，callId 关联工具响应，不表示已执行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record ToolCall(String callId, DefinitionRef tool,
                       StructuredValue.ObjectValue arguments) implements Serializable {
    public ToolCall {
        ContractChecks.id(callId, "callId");
        Objects.requireNonNull(tool, "tool").requireType("tool");
        Objects.requireNonNull(arguments, "arguments");
    }
}
