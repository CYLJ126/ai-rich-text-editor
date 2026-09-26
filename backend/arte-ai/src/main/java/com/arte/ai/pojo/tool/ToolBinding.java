package com.arte.ai.pojo.tool;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 工具定义在某用户或工作空间内的运行时绑定实体。
 *
 * <p>绑定只保存非敏感配置和凭据引用，不保存明文密钥。修改方法返回新实体，便于审计和并发控制。
 */
public final class ToolBinding {

    private final String bindingId;
    private final String ownerId;
    private final ToolReference tool;
    private final boolean enabled;
    private final String credentialReference;
    private final Map<String, Object> configuration;
    private final ToolExecutionPolicy policyOverride;
    private final long version;
    private final Instant updatedAt;

    public ToolBinding(
            String bindingId,
            String ownerId,
            ToolReference tool,
            boolean enabled,
            String credentialReference,
            Map<String, Object> configuration,
            ToolExecutionPolicy policyOverride,
            long version,
            Instant updatedAt
    ) {
        this.bindingId = requireText(bindingId, "bindingId");
        this.ownerId = requireText(ownerId, "ownerId");
        this.tool = Objects.requireNonNull(tool, "tool must not be null");
        this.enabled = enabled;
        this.credentialReference = credentialReference;
        this.configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
        this.policyOverride = policyOverride;
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static ToolBinding create(
            String bindingId,
            String ownerId,
            ToolReference tool,
            String credentialReference,
            Map<String, Object> configuration,
            Instant now
    ) {
        return new ToolBinding(bindingId, ownerId, tool, true, credentialReference,
                configuration, null, 0, now);
    }

    public ToolBinding enable(Instant now) {
        return copy(true, credentialReference, configuration, policyOverride, now);
    }

    public ToolBinding disable(Instant now) {
        return copy(false, credentialReference, configuration, policyOverride, now);
    }

    public ToolBinding rotateCredential(String reference, Instant now) {
        return copy(enabled, requireText(reference, "credentialReference"), configuration, policyOverride, now);
    }

    public ToolBinding updateConfiguration(Map<String, Object> value, Instant now) {
        return copy(enabled, credentialReference, value, policyOverride, now);
    }

    public ToolBinding changePolicy(ToolExecutionPolicy value, Instant now) {
        return copy(enabled, credentialReference, configuration, value, now);
    }

    private ToolBinding copy(
            boolean newEnabled,
            String newCredentialReference,
            Map<String, Object> newConfiguration,
            ToolExecutionPolicy newPolicyOverride,
            Instant now
    ) {
        return new ToolBinding(bindingId, ownerId, tool, newEnabled, newCredentialReference,
                newConfiguration, newPolicyOverride, version + 1, now);
    }

    public String bindingId() {
        return bindingId;
    }

    public String ownerId() {
        return ownerId;
    }

    public ToolReference tool() {
        return tool;
    }

    public boolean enabled() {
        return enabled;
    }

    public String credentialReference() {
        return credentialReference;
    }

    public Map<String, Object> configuration() {
        return configuration;
    }

    public ToolExecutionPolicy policyOverride() {
        return policyOverride;
    }

    public long version() {
        return version;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
