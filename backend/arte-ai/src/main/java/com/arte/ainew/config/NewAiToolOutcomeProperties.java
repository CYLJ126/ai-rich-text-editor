package com.arte.ainew.config;

import com.arte.ainew.common.validation.ContractChecks;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 自动业务结果核对默认关闭；不会开放业务执行或人工证据入口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 10:48 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new-tool-outcome", ignoreUnknownFields = false)
public record NewAiToolOutcomeProperties(boolean enabled, Duration pollInterval, int batchSize) {

    public NewAiToolOutcomeProperties {
        pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
        ContractChecks.require(pollInterval.compareTo(Duration.ofMillis(100)) >= 0 && pollInterval.compareTo(Duration.ofMinutes(1)) <= 0, "Invalid outcome poll interval");
        batchSize = batchSize == 0 ? 16 : batchSize;
        ContractChecks.range(batchSize, "outcome batch size", 1, 64);
    }
}
