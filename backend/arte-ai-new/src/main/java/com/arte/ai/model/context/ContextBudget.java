package com.arte.ai.model.context;

/**
 * 最小文本上下文的容量事实。inputByteLimit／usedInputBytes 以消息文本 UTF-8 字节计量；
 * outputTokenReserve 单独以 token 计量，不相加不同单位。协议正文上限由网关另外检查。
 * 结构检查不证明计量准确，组装服务须计算实际消息文本容量。
 */
public record ContextBudget(int inputByteLimit, int usedInputBytes, int outputTokenReserve) {
    public ContextBudget {
        if (inputByteLimit <= 0 || usedInputBytes < 0 || usedInputBytes > inputByteLimit || outputTokenReserve <= 0) {
            throw new IllegalArgumentException("invalid context byte budget or output token reserve");
        }
    }
}
