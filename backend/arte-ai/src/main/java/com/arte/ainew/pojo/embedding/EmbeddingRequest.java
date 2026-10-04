package com.arte.ainew.pojo.embedding;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.CapabilityInput;
import java.io.Serializable;
import java.util.List;

/** 批量文本向量输入，稳定 inputId 用于对应输出；模型与实际批量限制由能力／绑定确定。 */
public record EmbeddingRequest(List<Input> inputs) implements CapabilityInput {
    public record Input(String inputId, String text) implements Serializable {
        public Input {
            ContractChecks.id(inputId, "inputId");
            ContractChecks.text(text, "text", ContractChecks.MAX_TEXT_CHARS);
        }
    }
    public EmbeddingRequest {
        inputs = ContractChecks.list(inputs, "inputs", 1, ContractChecks.MAX_ITEMS);
        ContractChecks.unique(inputs.stream().map(Input::inputId).toList(), "input IDs");
        ContractChecks.require(inputs.stream().mapToLong(input -> input.text().length()).sum() <= ContractChecks.MAX_TEXT_CHARS,
                "Embedding input exceeds character limit");
    }
    @Override public CapabilityDescriptor.Kind kind() { return CapabilityDescriptor.Kind.EMBEDDING; }
}
