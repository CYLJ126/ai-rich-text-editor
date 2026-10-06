package com.arte.ainew.config;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 第 4 步独立开关及受信 HTTP 配置；不在配置或日志中保存明文凭据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new-generation", ignoreUnknownFields = false)
public record NewAiGenerationProperties(boolean enabled, List<URI> allowedOrigins, List<SecretEnvironment> secrets,
                                        int maxPools, int connectionsPerPool, int pendingAcquires,
                                        Duration acquireTimeout, int maxFrameBytes, int maxRequestBytes) {
    /**
     * 仅固定版本 SecretRef 到环境变量名称的映射；环境变量值在实际发送时读取。
     */
    public record SecretEnvironment(DefinitionRef reference, String environmentVariable) {
        public SecretEnvironment {
            Objects.requireNonNull(reference).requireType("secret");
            ContractChecks.require(environmentVariable != null && environmentVariable.matches("[A-Z_][A-Z0-9_]{0,127}"),
                    "Invalid credential environment variable name");
        }
    }

    public NewAiGenerationProperties {
        allowedOrigins = allowedOrigins == null ? List.of(URI.create("https://api.deepseek.com"))
                : ContractChecks.list(allowedOrigins, "allowedOrigins", 1, 64);
        ContractChecks.unique(allowedOrigins, "allowedOrigins");
        for (var origin : allowedOrigins) {
            ContractChecks.require(origin.getHost() != null && origin.getUserInfo() == null && origin.getRawQuery() == null
                            && origin.getRawFragment() == null && (origin.getPath().isEmpty() || origin.getPath().equals("/"))
                            && ("https".equals(origin.getScheme()) || "http".equals(origin.getScheme())
                            && ("127.0.0.1".equals(origin.getHost()) || "localhost".equals(origin.getHost()))),
                    "Origin must be HTTPS, or an explicitly allowed HTTP loopback test origin");
        }
        secrets = secrets == null ? List.of() : ContractChecks.list(secrets, "secrets", 0, 256);
        ContractChecks.unique(secrets.stream().map(SecretEnvironment::reference).toList(), "secret references");
        maxPools = maxPools == 0 ? 32 : (int) ContractChecks.range(maxPools, "maxPools", 1, 256);
        connectionsPerPool = connectionsPerPool == 0 ? 8 : (int) ContractChecks.range(connectionsPerPool, "connectionsPerPool", 1, 64);
        pendingAcquires = pendingAcquires == 0 ? 32 : (int) ContractChecks.range(pendingAcquires, "pendingAcquires", 1, 1024);
        acquireTimeout = acquireTimeout == null ? Duration.ofSeconds(5) : acquireTimeout;
        ContractChecks.require(acquireTimeout.compareTo(Duration.ofMillis(1)) >= 0
                && acquireTimeout.compareTo(Duration.ofSeconds(30)) <= 0, "Invalid acquire timeout");
        maxFrameBytes = maxFrameBytes == 0 ? 65_536 : (int) ContractChecks.range(maxFrameBytes, "maxFrameBytes", 1024, 1_048_576);
        maxRequestBytes = maxRequestBytes == 0 ? 1_048_576 : (int) ContractChecks.range(maxRequestBytes, "maxRequestBytes", 1024, 4_194_304);
    }
}
