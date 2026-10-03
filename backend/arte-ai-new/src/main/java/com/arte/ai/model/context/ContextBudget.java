package com.arte.ai.model.context;

/**
 * 最小文本上下文的容量事实。inputByteLimit／usedInputBytes 以消息文本 UTF-8 字节计量；
 * outputTokenReserve 单独以 token 计量，不相加不同单位。协议正文上限由网关另外检查。
 * 结构检查不证明计量准确，组装服务须计算实际消息文本容量。
 */
public record ContextBudget(int inputByteLimit, int usedInputBytes, int outputTokenReserve,
                            Integer contextWindowTokens, Integer inputTokenLimit, Integer estimatedInputTokens,
                            Integer safetyTokenReserve, String estimatorVersion) {
    public ContextBudget(int inputByteLimit, int usedInputBytes, int outputTokenReserve) {
        this(inputByteLimit, usedInputBytes, outputTokenReserve, null, null, null, null, null);
    }
    public ContextBudget {
        if (inputByteLimit <= 0 || usedInputBytes < 0 || usedInputBytes > inputByteLimit || outputTokenReserve <= 0) {
            throw new IllegalArgumentException("invalid context byte budget or output token reserve");
        }
        if (contextWindowTokens != null) {
            if (inputTokenLimit == null || estimatedInputTokens == null || safetyTokenReserve == null || estimatorVersion == null
                    || contextWindowTokens <= 0 || inputTokenLimit <= 0 || estimatedInputTokens < 0 || estimatedInputTokens > inputTokenLimit
                    || safetyTokenReserve < 0 || (long) inputTokenLimit + outputTokenReserve + safetyTokenReserve != contextWindowTokens)
                throw new IllegalArgumentException("invalid context token budget");
            com.arte.base.validation.ContractChecks.identifier(estimatorVersion, "estimatorVersion");
        } else if (inputTokenLimit != null || estimatedInputTokens != null || safetyTokenReserve != null || estimatorVersion != null)
            throw new IllegalArgumentException("incomplete token budget");
    }
}
