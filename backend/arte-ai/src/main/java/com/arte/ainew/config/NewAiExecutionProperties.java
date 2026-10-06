package com.arte.ainew.config;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.ExecutionCommands;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
                                       Duration reservationRetention) {
    public NewAiExecutionProperties {
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
}
