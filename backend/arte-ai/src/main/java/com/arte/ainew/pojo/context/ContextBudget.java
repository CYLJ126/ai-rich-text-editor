package com.arte.ainew.pojo.context;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;

/**
 * Token 容量约束，依据固定模型绑定解析。预留输出与工具结果空间；不是计费用量或字符数量。
 *
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
