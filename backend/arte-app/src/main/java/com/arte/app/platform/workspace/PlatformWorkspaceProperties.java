package com.arte.app.platform.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工作空间服务开关；新个人租户政策只从数据库的版本化模板读取。
 */
@Deprecated
@ConfigurationProperties(prefix = "arte.platform.workspace", ignoreUnknownFields = false)
public record PlatformWorkspaceProperties(boolean enabled) {
}
