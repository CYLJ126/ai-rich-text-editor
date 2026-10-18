package com.arte.ainew.config;

import com.arte.ainew.common.validation.ContractChecks;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * TOOL 独立开关及本地发现限制；只接受本批已实现的配置项，不启动发现、发布或执行。
 *
 * @param enabled   默认关闭；开启仅装配本地发现服务，仍须显式登记来源并手动刷新。
 * @param discovery 本地来源读取限制。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:30
 */
@ConfigurationProperties(prefix = "arte.ai-new-tool", ignoreUnknownFields = false)
public record NewAiToolProperties(boolean enabled, Discovery discovery) {
    public NewAiToolProperties {
        discovery = discovery == null ? new Discovery(null) : discovery;
    }

    /**
     * @param sourceTimeout 每个完整来源的读取期限，默认 10 秒，范围为 1 毫秒至 1 分钟。
     */
    public record Discovery(Duration sourceTimeout) {

        public Discovery {
            sourceTimeout = sourceTimeout == null ? Duration.ofSeconds(10) : sourceTimeout;
            ContractChecks.require(sourceTimeout.compareTo(Duration.ofMillis(1)) >= 0
                    && sourceTimeout.compareTo(Duration.ofMinutes(1)) <= 0, "Invalid discovery source timeout");
        }
    }
}
