package com.arte.ainew.config;

import com.arte.ainew.common.validation.ContractChecks;

import java.time.Duration;

/**
 * 单个 HTTP 运行时的连接池、等待及解码资源参数
 * <p>
 * 模型、HTTP 工具和 MCP 工具共用参数结构，各自创建运行实例。
 * 配置绑定时数值 0 和空超时表示未设置，由 {@link NewAiHttpProperties} 按所属参数组补齐默认值。
 * 传入连接运行时前必须完成补齐；这里不保存出口白名单、凭据、模型协议限制或连接业务定义。
 *
 * @param maxPools           单个运行时保留的连接池数量上限
 * @param connectionsPerPool 每个连接池的连接数上限
 * @param pendingAcquires    每个连接池等待获取连接的请求数上限
 * @param acquireTimeout     DNS 解析和等待连接各自的超时时长
 * @param maxInMemoryBytes   HTTP 客户端单次解码缓冲的字节上限，不代表整条响应或 SSE 帧的协议上限
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 14:25 ✾
 */
public record HttpRuntimeOptions(
        int maxPools,
        int connectionsPerPool,
        int pendingAcquires,
        Duration acquireTimeout,
        int maxInMemoryBytes) {

    public HttpRuntimeOptions {
        ContractChecks.range(maxPools, "maxPools", 0, 256);
        ContractChecks.range(connectionsPerPool, "connectionsPerPool", 0, 64);
        ContractChecks.range(pendingAcquires, "pendingAcquires", 0, 1024);
        if (acquireTimeout != null) {
            ContractChecks.require(acquireTimeout.compareTo(Duration.ofMillis(1)) >= 0
                    && acquireTimeout.compareTo(Duration.ofSeconds(30)) <= 0, "Invalid HTTP acquire timeout");
        }
        ContractChecks.require(maxInMemoryBytes == 0 || maxInMemoryBytes >= 1024 && maxInMemoryBytes <= 1_048_576,
                "Invalid HTTP decode buffer limit");
    }

    public static HttpRuntimeOptions modelDefaults() {
        return new HttpRuntimeOptions(32, 8, 32, Duration.ofSeconds(5), 65_536);
    }

    public static HttpRuntimeOptions toolDefaults() {
        return new HttpRuntimeOptions(8, 4, 8, Duration.ofSeconds(2), 65_536);
    }

    /**
     * 补齐当前参数组缺省项；没有跨组继承或多级配置覆盖。
     */
    static HttpRuntimeOptions resolve(HttpRuntimeOptions configured, HttpRuntimeOptions defaults) {
        if (configured == null) return defaults;
        return new HttpRuntimeOptions(configured.maxPools == 0 ? defaults.maxPools : configured.maxPools,
                configured.connectionsPerPool == 0 ? defaults.connectionsPerPool : configured.connectionsPerPool,
                configured.pendingAcquires == 0 ? defaults.pendingAcquires : configured.pendingAcquires,
                configured.acquireTimeout == null ? defaults.acquireTimeout : configured.acquireTimeout,
                configured.maxInMemoryBytes == 0 ? defaults.maxInMemoryBytes : configured.maxInMemoryBytes);
    }

    /**
     * 运行时只接受已补齐的资源参数，避免未完成配置绑定的对象进入连接池。
     */
    public HttpRuntimeOptions requireComplete() {
        ContractChecks.require(maxPools > 0 && connectionsPerPool > 0 && pendingAcquires > 0
                && acquireTimeout != null && maxInMemoryBytes > 0, "Complete HTTP runtime options are required");
        return this;
    }
}
