package com.arte.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具集群同步配置。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "arte.ai.tool.cluster")
public class ToolClusterProperties {

    private boolean enabled = true;
    private String instanceId;
    private String topic = "arte:ai:tool:catalog-events";
    private String syncLockPrefix = "arte:ai:tool:provider-sync:";
    private long syncLockWaitMillis = 10000L;
}
