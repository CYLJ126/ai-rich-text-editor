package com.arte.ainew.config;

import com.arte.ainew.common.validation.ContractChecks;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 工具聊天独立开放与恢复开关；默认关闭，开启前须完成 S4-02～04 DDL 及全部节点升级。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 22:00 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new-tool-chat", ignoreUnknownFields = false)
public record NewAiToolChatProperties(boolean enabled, boolean workerEnabled, Duration pollInterval,
                                      Duration lease, int batchSize) {

    public NewAiToolChatProperties {
        ContractChecks.require(!workerEnabled || enabled, "Run worker requires tool chat to be enabled");
        pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
        lease = lease == null ? Duration.ofSeconds(45) : lease;
        batchSize = batchSize == 0 ? 8 : batchSize;
        ContractChecks.require(pollInterval.compareTo(Duration.ofMillis(100)) >= 0 && pollInterval.compareTo(Duration.ofSeconds(30)) <= 0,
                "Run polling must be within 100 ms and 30 seconds");
        ContractChecks.require(lease.compareTo(Duration.ofSeconds(10)) >= 0 && lease.compareTo(Duration.ofMinutes(1)) <= 0,
                "Run lease must be within 10 seconds and 1 minute");
        ContractChecks.range(batchSize, "Run claim batch", 1, 32);
    }
}
