package com.arte.ainew.pojo.execution;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 执行约束上限
 * <p>
 * 用于规定一次逻辑调用的执行上限：截止时间、最多尝试次数、输出字节数、工具步骤数、工具并发数及原始请求超时。
 * 这些是约束条件，不代表自动获得重试或工具执行资格。
 * requestedTimeout 还用于保持幂等请求语义，重复提交不会延长原调用期限。
 * maxAttempts 包含首次尝试；0 个工具步骤／并发表示禁止工具。
 *
 * @param deadline           本次逻辑调用的绝对截止时间，不能为空，包含排队及执行耗时；不得晚于 ExecutionContext 的总期限，重试或恢复不能延长原调用期限。
 * @param maxAttempts        最多允许的执行尝试次数，范围为 1～10，包含首次 Attempt；实际重试仍受当前策略、发送事实、副作用、预算及剩余期限约束。
 * @param maxOutputBytes     本次执行输出的字节数上限，范围为 1～32 MiB，具体输出按相应链路的 UTF-8 编码及持久化规则校验；不是输出 Token 数或网络响应总字节数。
 * @param maxToolSteps       本次调用允许的工具步骤数量上限，范围为 0～100；当前模型链路也用它限制工具调用提议数量，不代替 Run 的累计工具配额；为 0 时 maxConcurrentTools 也必须为 0。
 * @param maxConcurrentTools 本次调用允许的工具并发数量上限，范围为 0～16，由对应工具执行流程落实；必须与 maxToolSteps 同为 0 或同为正数，不表示当前已有工具正在执行。
 * @param requestedTimeout   可选的原始相对超时时长，非空时范围为 1 秒至 1 小时，用于固定请求语义及幂等摘要；为空时按绝对 deadline 校验重放，非空时重放仍沿用原受理期限，不重新计时。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record ExecutionOptions(Instant deadline, int maxAttempts, long maxOutputBytes,
                               int maxToolSteps, int maxConcurrentTools,
                               Duration requestedTimeout) implements Serializable {
    /**
     * 兼容原始绝对期限契约；需要在重新分配身份／期限后重放的入口须显式提供 requestedTimeout。
     */
    public ExecutionOptions(Instant deadline, int maxAttempts, long maxOutputBytes, int maxToolSteps, int maxConcurrentTools) {
        this(deadline, maxAttempts, maxOutputBytes, maxToolSteps, maxConcurrentTools, null);
    }

    public ExecutionOptions {
        Objects.requireNonNull(deadline, "deadline");
        ContractChecks.range(maxAttempts, "maxAttempts", 1, 10);
        ContractChecks.range(maxOutputBytes, "maxOutputBytes", 1, 32L * 1024 * 1024);
        ContractChecks.range(maxToolSteps, "maxToolSteps", 0, 100);
        ContractChecks.range(maxConcurrentTools, "maxConcurrentTools", 0, 16);
        ContractChecks.require((maxToolSteps == 0) == (maxConcurrentTools == 0),
                "Tool steps and concurrency must both be zero or positive");
        if (requestedTimeout != null) {
            ContractChecks.require(requestedTimeout.compareTo(Duration.ofSeconds(1)) >= 0
                    && requestedTimeout.compareTo(Duration.ofHours(1)) <= 0, "Requested timeout must be within 1 second and 1 hour");
        }
    }
}
