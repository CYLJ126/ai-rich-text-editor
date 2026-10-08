package com.arte.ainew.config;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.ExecutionCommands;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * 第 5～6 步独立开关；默认不启动 Worker，不创建账户、不执行 DDL。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 16:14 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new-execution", ignoreUnknownFields = false)
public record NewAiExecutionProperties(boolean enabled, boolean workerEnabled, int concurrency,
                                       Duration pollInterval, Duration outboxLease, Duration attemptLease,
                                       Duration reservationRetention, DispatchLimits dispatchLimits, Retry retry) {
    public NewAiExecutionProperties(boolean enabled, boolean workerEnabled, int concurrency,
                                    Duration pollInterval, Duration outboxLease, Duration attemptLease,
                                    Duration reservationRetention) {
        this(enabled, workerEnabled, concurrency, pollInterval, outboxLease, attemptLease, reservationRetention, null, null);
    }

    @ConstructorBinding
    public NewAiExecutionProperties {
        dispatchLimits = dispatchLimits == null ? new DispatchLimits(null, 0, 0, 0, 0, 0, 0, 0, 0, null) : dispatchLimits;
        retry = retry == null ? new Retry(0, null, null) : retry;
        concurrency = concurrency == 0 ? 4 : concurrency;
        ContractChecks.range(concurrency, "concurrency", 1, 32);
        pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
        ContractChecks.require(pollInterval.compareTo(Duration.ofMillis(100)) >= 0
                && pollInterval.compareTo(Duration.ofMinutes(1)) <= 0, "Invalid worker polling interval");
        outboxLease = outboxLease == null ? Duration.ofMinutes(2) : outboxLease;
        attemptLease = attemptLease == null ? Duration.ofMinutes(2) : attemptLease;
        ExecutionCommands.validLease(outboxLease);
        ExecutionCommands.validLease(attemptLease);
        ContractChecks.require(outboxLease.compareTo(Duration.ofSeconds(3)) >= 0
                && attemptLease.compareTo(Duration.ofSeconds(3)) >= 0, "Worker leases must allow bounded heartbeats");
        reservationRetention = reservationRetention == null ? Duration.ofDays(1) : reservationRetention;
        ContractChecks.require(reservationRetention.compareTo(Duration.ofSeconds(1)) >= 0
                && reservationRetention.compareTo(Duration.ofDays(30)) <= 0, "Invalid budget retention");
        ContractChecks.require(!workerEnabled || enabled, "Worker requires execution to be enabled");
    }

    /**
     * 同数据库的所有 Worker 共享；限流在预留预算和 markDispatch 前执行。
     */
    public record DispatchLimits(Boolean enabled, int globalConcurrency, int tenantConcurrency,
                                 int userConcurrency, int modelConcurrency, int globalRequests,
                                 int tenantRequests, int userRequests, int modelRequests, Duration window) {
        public DispatchLimits {
            enabled = enabled == null || enabled;
            globalConcurrency = positive(globalConcurrency, 16);
            tenantConcurrency = positive(tenantConcurrency, 8);
            userConcurrency = positive(userConcurrency, 2);
            modelConcurrency = positive(modelConcurrency, 8);
            globalRequests = positive(globalRequests, 120);
            tenantRequests = positive(tenantRequests, 60);
            userRequests = positive(userRequests, 20);
            modelRequests = positive(modelRequests, 60);
            window = window == null ? Duration.ofMinutes(1) : window;
            ContractChecks.require(window.compareTo(Duration.ofSeconds(1)) >= 0
                    && window.compareTo(Duration.ofHours(1)) <= 0, "Invalid dispatch rate window");
        }

        private static int positive(int value, int fallback) {
            int result = value == 0 ? fallback : value;
            ContractChecks.range(result, "dispatchLimit", 1, 1_000_000);
            return result;
        }
    }

    /**
     * 包含首次请求；退避由 Invocation + Attempt 确定，重启后不会缩短。
     */
    public record Retry(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {
        public Retry {
            maxAttempts = maxAttempts == 0 ? 3 : maxAttempts;
            ContractChecks.range(maxAttempts, "maxAttempts", 1, 10);
            initialBackoff = initialBackoff == null ? Duration.ofSeconds(1) : initialBackoff;
            maxBackoff = maxBackoff == null ? Duration.ofSeconds(10) : maxBackoff;
            ContractChecks.require(initialBackoff.compareTo(Duration.ofMillis(100)) >= 0
                            && maxBackoff.compareTo(initialBackoff) >= 0 && maxBackoff.compareTo(Duration.ofMinutes(1)) <= 0,
                    "Invalid retry backoff");
        }

        public Duration delay(String attemptId, int number) {
            long capped = Math.min(maxBackoff.toMillis(), initialBackoff.toMillis() * (1L << Math.min(number - 1, 10)));
            // 稳定的 80%～100% jitter：同一 Attempt 重投仍使用同一到期时间。
            return Duration.ofMillis(Math.max(1, capped * (80 + Math.floorMod(attemptId.hashCode(), 21)) / 100));
        }
    }
}
