package com.arte.ainew.config;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.core.pojo.reference.DefinitionRef;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * AI 生成网关的启动开关、HTTP 出口策略和传输限制。
 *
 * @param enabled                     是否在应用启动时装配生成网关，默认关闭
 * @param allowedOrigins              FIXED 模式和直接构造的工具 HTTP 运行时使用的出口白名单。DATABASE 模式的模型出口由平台策略管理
 * @param secrets                     固定版本的凭据引用到环境变量名的映射，默认为空，凭据值在实际发送时读取
 * @param maxPools                    单个 HTTP 运行时可保留的连接池数量上限，默认 32
 * @param connectionsPerPool          每个连接池的连接数上限，默认 8
 * @param pendingAcquires             每个连接池中等待获取连接的请求数上限，默认 32
 * @param acquireTimeout              DNS 解析和等待获取连接各自的超时，默认 5 秒
 * @param maxFrameBytes               单个 SSE 响应帧及解码缓冲区的字节上限，模型网关固定使用 64 KiB
 * @param maxRequestBytes             序列化后的单次 HTTP 请求体字节上限，模型网关固定使用 1 MiB
 * @param completedStreamDrainTimeout 收到 {@code [DONE]} 后继续读取 HTTP 响应尾部的最长时间，默认 100 毫秒
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new-generation", ignoreUnknownFields = false)
public record NewAiGenerationProperties(boolean enabled, List<URI> allowedOrigins, List<SecretEnvironment> secrets,
                                        int maxPools, int connectionsPerPool, int pendingAcquires,
                                        Duration acquireTimeout, int maxFrameBytes, int maxRequestBytes,
                                        Duration completedStreamDrainTimeout) {
    private static final int DEFAULT_MAX_FRAME_BYTES = 64 * 1024;
    private static final int DEFAULT_MAX_REQUEST_BYTES = 1024 * 1024;

    @ConstructorBinding
    public NewAiGenerationProperties(boolean enabled, List<URI> allowedOrigins, List<SecretEnvironment> secrets,
                                     int maxPools, int connectionsPerPool, int pendingAcquires,
                                     Duration acquireTimeout, Duration completedStreamDrainTimeout) {
        this(enabled, allowedOrigins, secrets, maxPools, connectionsPerPool, pendingAcquires, acquireTimeout,
                DEFAULT_MAX_FRAME_BYTES, DEFAULT_MAX_REQUEST_BYTES, completedStreamDrainTimeout);
    }

    public NewAiGenerationProperties(boolean enabled, List<URI> allowedOrigins, List<SecretEnvironment> secrets,
                                     int maxPools, int connectionsPerPool, int pendingAcquires,
                                     Duration acquireTimeout, int maxFrameBytes, int maxRequestBytes) {
        this(enabled, allowedOrigins, secrets, maxPools, connectionsPerPool, pendingAcquires, acquireTimeout,
                maxFrameBytes, maxRequestBytes, null);
    }

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
        maxFrameBytes = maxFrameBytes == 0 ? DEFAULT_MAX_FRAME_BYTES : (int) ContractChecks.range(maxFrameBytes, "maxFrameBytes", 1024, 1_048_576);
        maxRequestBytes = maxRequestBytes == 0 ? DEFAULT_MAX_REQUEST_BYTES : (int) ContractChecks.range(maxRequestBytes, "maxRequestBytes", 1024, 4_194_304);
        // DONE 后只为 HTTP 尾部预留短暂排空时间；首段文字不等待该阶段。
        completedStreamDrainTimeout = completedStreamDrainTimeout == null ? Duration.ofMillis(100) : completedStreamDrainTimeout;
        ContractChecks.require(completedStreamDrainTimeout.compareTo(Duration.ofMillis(1)) >= 0
                && completedStreamDrainTimeout.compareTo(Duration.ofSeconds(1)) <= 0, "Invalid completed stream drain timeout");
    }
}
