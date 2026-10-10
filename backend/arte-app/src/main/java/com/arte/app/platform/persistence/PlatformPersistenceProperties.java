package com.arte.app.platform.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "arte.platform.persistence", ignoreUnknownFields = false)
public record PlatformPersistenceProperties(
        boolean enabled,
        int workers,
        int queueCapacity,
        int queryTimeoutSeconds,
        int transactionTimeoutSeconds,
        int outboxBatchSize,
        Duration lease,
        Duration pollInterval,
        Duration retryDelay,
        Duration cursorTtl,
        String redisPrefix,
        boolean outboxEnabled) {
    public PlatformPersistenceProperties {
        workers = workers == 0 ? 8 : workers;
        queueCapacity = queueCapacity == 0 ? 128 : queueCapacity;
        queryTimeoutSeconds = queryTimeoutSeconds == 0 ? 5 : queryTimeoutSeconds;
        transactionTimeoutSeconds = transactionTimeoutSeconds == 0 ? 15 : transactionTimeoutSeconds;
        outboxBatchSize = outboxBatchSize == 0 ? 50 : outboxBatchSize;
        lease = lease == null ? Duration.ofSeconds(30) : lease;
        pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
        retryDelay = retryDelay == null ? Duration.ofSeconds(5) : retryDelay;
        cursorTtl = cursorTtl == null ? Duration.ofMinutes(10) : cursorTtl;
        redisPrefix = redisPrefix == null ? "arte:platform:" : redisPrefix;
        if (workers < 1
                || workers > 128
                || queueCapacity < 1
                || queryTimeoutSeconds < 1
                || transactionTimeoutSeconds < queryTimeoutSeconds
                || outboxBatchSize < 1
                || outboxBatchSize > 100
                || lease.compareTo(Duration.ofSeconds(5)) < 0
                || pollInterval.isZero()
                || pollInterval.isNegative()
                || retryDelay.isNegative()
                || cursorTtl.isNegative()
                || cursorTtl.isZero()
                || redisPrefix.isBlank())
            throw new IllegalArgumentException("Invalid platform persistence limits");
    }
}
