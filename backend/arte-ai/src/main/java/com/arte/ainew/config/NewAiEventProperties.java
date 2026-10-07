package com.arte.ainew.config;

import com.arte.ainew.common.validation.ContractChecks;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AI 执行事件通知配置，对应 {@code arte.ai-new-events} 配置前缀。
 * 通知仅用于唤醒订阅端，权威事件由数据库重放；共享数据库的实例应使用相同传输方式和通知通道。
 *
 * @param transport      通知传输方式，默认 {@link Transport#LOCAL}：仅唤醒当前进程的订阅者，适用于单实例；
 *                       {@link Transport#REDIS} 复用已有 RedissonClient 广播至各实例，适用于多实例部署。
 * @param channel        Redis 广播通道名称，默认 {@code arte:ai-new:events:v1}；显式配置时必须非空白且不超过 256 个字符。
 *                       仅 REDIS 模式使用；共享数据库的实例须保持一致，不同环境应使用不同通道。
 * @param publishTimeout 单次 Redis 通知发布的等待上限，默认 3 秒，允许范围为 100 毫秒至 30 秒（含边界）；
 *                       REDIS 模式下还必须短于 Outbox 租约。超时或发布失败时不确认 EVENT Outbox，等待恢复重试；
 *                       此值不控制模型执行超时或 SSE 连接期限。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 00:06 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new-events", ignoreUnknownFields = false)
public record NewAiEventProperties(Transport transport, String channel, Duration publishTimeout) {
    /**
     * 通知传输方式。
     * 配置项由 {@see org.springframework.boot.convert.LenientStringToEnumConverterFactory} 解析，支持大小写不敏感。
     */
    public enum Transport {LOCAL, REDIS}

    public NewAiEventProperties {
        transport = transport == null ? Transport.LOCAL : transport;
        channel = channel == null ? "arte:ai-new:events:v1" : ContractChecks.text(channel, "channel", 256);
        publishTimeout = publishTimeout == null ? Duration.ofSeconds(3) : publishTimeout;
        ContractChecks.require(publishTimeout.compareTo(Duration.ofMillis(100)) >= 0
                && publishTimeout.compareTo(Duration.ofSeconds(30)) <= 0, "Invalid event publish timeout");
    }
}
