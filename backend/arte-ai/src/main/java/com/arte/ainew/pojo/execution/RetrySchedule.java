package com.arte.ainew.pojo.execution;

import com.arte.ainew.common.validation.ContractChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 与失败 Attempt 和 QUEUED 同事务保存；配置变更不能提前旧重试。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 22:05 ✾
 */
public record RetrySchedule(String attemptId, Instant notBefore) {

    public RetrySchedule {
        ContractChecks.id(attemptId, "attemptId");
        Objects.requireNonNull(notBefore, "notBefore");
    }
}
