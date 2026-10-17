package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.time.Duration;
import java.util.Objects;

/**
 * 工具默认执行约束；绑定与调用只能进一步收紧，运行时还受当前服务器上限和剩余期限约束。
 * 次数上限不代表允许自动重试，安全重试仍须结合派发事实和幂等依据判定。
 *
 * @param timeout        一次逻辑调用的总期限预算，包含排队及人工等待，不是每个 Attempt 都重新获得的时长。
 * @param maxAttempts    最多尝试次数，包含首次；不使用 maxRetries 的另一套计数。
 * @param retryBackoff   允许重试时的最低退避，不能为负或超过总期限。
 * @param maxInputBytes  编码后的输入字节上限，不等于字符数或 Token。
 * @param maxOutputBytes 编码后的输出字节上限，结果保存边界仍须校验实际大小。
 * @param approvalMode   最低人工控制要求，不替代当前授权或具体审批决定。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17
 */
public record ToolExecutionPolicy(Duration timeout, int maxAttempts, Duration retryBackoff,
                                  long maxInputBytes, long maxOutputBytes,
                                  ApprovalMode approvalMode) implements Serializable {
    public static final long MAX_INPUT_BYTES = 1_000_000;
    public static final long MAX_OUTPUT_BYTES = 32L * 1024 * 1024;

    /**
     * 人工控制强度；独立审批可以满足本人确认的最低要求，反向不能替代。
     */
    public enum ApprovalMode {
        NONE, REQUESTER_CONFIRMATION, INDEPENDENT_APPROVAL;

        public boolean satisfies(ApprovalMode minimum) {
            Objects.requireNonNull(minimum, "minimum");
            return minimum == NONE || this == minimum || this == INDEPENDENT_APPROVAL;
        }
    }

    public ToolExecutionPolicy {
        Objects.requireNonNull(timeout, "timeout");
        ContractChecks.require(timeout.compareTo(Duration.ofSeconds(1)) >= 0
                        && timeout.compareTo(Duration.ofHours(1)) <= 0,
                "Tool timeout must be within 1 second and 1 hour");
        ContractChecks.range(maxAttempts, "maxAttempts", 1, 10);
        Objects.requireNonNull(retryBackoff, "retryBackoff");
        ContractChecks.require(!retryBackoff.isNegative() && retryBackoff.compareTo(timeout) <= 0,
                "Retry backoff must be nonnegative and within tool timeout");
        ContractChecks.range(maxInputBytes, "maxInputBytes", 1, MAX_INPUT_BYTES);
        ContractChecks.range(maxOutputBytes, "maxOutputBytes", 1, MAX_OUTPUT_BYTES);
        Objects.requireNonNull(approvalMode, "approvalMode");
    }
}
