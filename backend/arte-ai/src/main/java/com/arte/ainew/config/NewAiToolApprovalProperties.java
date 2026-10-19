package com.arte.ainew.config;

import com.arte.ainew.common.validation.ContractChecks;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 安装审批表并统一升级节点后显式开启恢复；开关不开放任何工具或审批 HTTP 权限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 00:05 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new-tool-approval", ignoreUnknownFields = false)
public record NewAiToolApprovalProperties(boolean enabled, Duration pollInterval, int batchSize) {
    public NewAiToolApprovalProperties {
        pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
        ContractChecks.require(pollInterval.compareTo(Duration.ofMillis(100)) >= 0
                && pollInterval.compareTo(Duration.ofMinutes(1)) <= 0, "Invalid approval recovery interval");
        batchSize = batchSize == 0 ? 64 : batchSize;
        ContractChecks.range(batchSize, "approval recovery batch", 1, 256);
    }
}
