package com.arte.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 工具调用管道和延迟任务 Worker 配置。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "arte.ai.tool.execution")
public class ToolExecutionProperties {

    private boolean bindingRequired = true;
    private int rateLimitPerMinute = 60;
    private int maximumInputBytes = 262144;
    private String rateLimitKeyPrefix = "arte:ai:tool:rate:";
    private int recoveryBatchSize = 100;
    private Duration workerLease = Duration.ofSeconds(60);
    private Duration leaseRenewInterval = Duration.ofSeconds(20);
    private Duration recoveryInterval = Duration.ofSeconds(10);
    private Duration recoveryInitialDelay = Duration.ofSeconds(5);
    private Duration approvalTimeout = Duration.ofHours(24);
    private Duration approvalExpiryScanInterval = Duration.ofSeconds(30);
    private Duration workflowLease = Duration.ofSeconds(60);
    private Duration workflowLeaseRenewInterval = Duration.ofSeconds(20);
    private Duration workflowRecoveryInterval = Duration.ofSeconds(10);
    private Duration workflowRecoveryInitialDelay = Duration.ofSeconds(10);
    private int workflowRecoveryBatchSize = 100;
}
