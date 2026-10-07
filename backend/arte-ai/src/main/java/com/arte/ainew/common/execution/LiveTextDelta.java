package com.arte.ainew.common.execution;

import com.arte.ainew.common.validation.ContractChecks;

import java.util.Objects;

/**
 * 模型文字的非耐久预览，不携带事件 sequence，也不证明调用成功或已结算。
 * offset 是本 Attempt 输出中的 UTF-16 起始偏移；重复／乱序或缺失片段由浏览器结合耐久 OUTPUT 修复。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 05:00 ✾
 */
public record LiveTextDelta(ExecutionOwner owner, String executionId, String attemptId, int offset, String text) {
    public static final int MAX_CHARS = 256;

    public LiveTextDelta {
        Objects.requireNonNull(owner, "owner");
        ContractChecks.id(executionId, "executionId");
        ContractChecks.id(attemptId, "attemptId");
        Objects.requireNonNull(text, "text");
        ContractChecks.range(text.length(), "text.length", 1, MAX_CHARS);
        ContractChecks.range(offset, "offset", 0, ContractChecks.MAX_TEXT_CHARS - text.length());
    }
}
