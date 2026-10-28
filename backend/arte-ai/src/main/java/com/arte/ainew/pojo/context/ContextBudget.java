package com.arte.ainew.pojo.context;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;

/**
 * Token 容量约束
 * <p>
 * 依据固定模型绑定解析；预留输出与工具结果空间；不是计费用量或字符数量。
 * 输入上限、输出预留及工具预留之和不得超过模型上下文窗口。
 *
 * @param contextWindowTokens  固定模型绑定的上下文窗口总容量，范围为 1～10,000,000 Token，组装及受理时须与实际绑定一致。
 * @param maxInputTokens       本次最终输入的 Token 数量上限，范围为 1 至 contextWindowTokens，覆盖实际编入的消息、历史及资料；不是已经使用的 Token 数量。
 * @param reservedOutputTokens 为模型输出预留的 Token 容量，范围为 1 至 contextWindowTokens；生成请求的 maxOutputTokens 不得超过此值，不代表实际输出用量。
 * @param reservedToolTokens   为后续工具结果进入上下文预留的 Token 容量，范围为 0 至 contextWindowTokens；为 0 表示不预留，当前普通文本上下文组装链路要求为 0。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record ContextBudget(long contextWindowTokens, long maxInputTokens,
                            long reservedOutputTokens, long reservedToolTokens) implements Serializable {

    public ContextBudget {
        ContractChecks.range(contextWindowTokens, "contextWindowTokens", 1, 10_000_000);
        ContractChecks.range(maxInputTokens, "maxInputTokens", 1, contextWindowTokens);
        ContractChecks.range(reservedOutputTokens, "reservedOutputTokens", 1, contextWindowTokens);
        ContractChecks.range(reservedToolTokens, "reservedToolTokens", 0, contextWindowTokens);
        ContractChecks.require(maxInputTokens + reservedOutputTokens + reservedToolTokens <= contextWindowTokens,
                "Input plus reserved output/tool tokens exceeds context capacity");
    }
}
