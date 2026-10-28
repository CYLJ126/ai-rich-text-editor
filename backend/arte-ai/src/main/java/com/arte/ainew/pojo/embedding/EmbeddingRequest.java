package com.arte.ainew.pojo.embedding;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.CapabilityInput;

import java.io.Serializable;
import java.util.List;

/**
 * 批量文本向量化请求
 * <p>
 * 作为 InvocationRequest 的 EMBEDDING 类型输入，描述需要转换为向量的一组文本。
 * 可用于为语义检索、索引或相似度计算提供向量输入；模型及实际批量、容量限制由能力、绑定和执行入口确定。
 * 输入不采用聊天消息格式，不携带供应商地址或凭据；构造成功仅表示结构合法，不表示已授权或完成向量计算。
 * 稳定 inputId 用于关联输出，EmbeddingResult.validateAgainst 须核对向量结果的数量、顺序及输入标识。
 *
 * @param inputs 待向量化的有序输入列表，不能为空，数量为 1 至 ContractChecks.MAX_ITEMS，inputId 不重复，保存为不可变列表；全部文本的 UTF-16 单元总数不得超过 ContractChecks.MAX_TEXT_CHARS。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record EmbeddingRequest(List<Input> inputs) implements CapabilityInput {

    /**
     * 批量向量请求中的单条文本输入，通过 inputId 与对应的 EmbeddingResult.Vector 关联。
     * 文本内容由调用方明确提供，资源读取及外发授权须在可信边界另行校验，本对象不解析来源或加载全文。
     *
     * @param inputId 本条输入的稳定标识，不能为空白且不超过 256 个 UTF-16 单元，在所属请求内唯一；用于对应输出向量，不要求是资源的全局 ID。
     * @param text    本条实际参与向量计算的文本，不能为空白且长度不超过 ContractChecks.MAX_TEXT_CHARS 个 UTF-16 单元；该字符限制不是 Token 配额，实际模型容量仍须另行校验。
     */
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

    @Override
    public CapabilityDescriptor.Kind kind() {
        return CapabilityDescriptor.Kind.EMBEDDING;
    }
}
