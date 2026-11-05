package com.arte.app.platform.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 组织创建必须显式开启，并提供独立的版本化初始政策 JSON。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 17:11 ✾
 */
@ConfigurationProperties("arte.platform.tenant-governance")
public record TenantGovernanceProperties(boolean organizationCreationEnabled, String organizationPolicyTemplate) {
}
