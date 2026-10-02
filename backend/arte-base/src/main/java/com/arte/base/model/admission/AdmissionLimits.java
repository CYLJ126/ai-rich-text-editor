package com.arte.base.model.admission;

import com.arte.base.validation.ContractChecks;

import java.time.Duration;

/**
 * 正整数请求数／时间窗；释放并发许可不会返还已消费的速率配额。
 */
public record AdmissionLimits(int maxConcurrent, int startsPerWindow, Duration window) {
    public AdmissionLimits {
        window = ContractChecks.required(window, "window");
        if (maxConcurrent <= 0 || startsPerWindow <= 0 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("admission limits must be positive");
        }
    }
}
