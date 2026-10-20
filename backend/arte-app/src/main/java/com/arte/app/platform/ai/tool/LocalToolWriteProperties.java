package com.arte.app.platform.ai.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 业务写工具默认关闭；S3-04／05 核对闭环验收前仅供隔离开发验收显式启用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 09:48 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new-tool-write", ignoreUnknownFields = false)
public record LocalToolWriteProperties(boolean enabled) {
}
