package com.arte.ai.service.tool.cluster;

import com.arte.ai.api.tool.ToolClusterEventPublisher;
import com.arte.ai.common.enums.tool.ToolClusterEventTypeEnum;
import com.arte.ai.config.ToolClusterProperties;
import com.arte.ai.pojo.tool.ToolClusterEvent;
import com.arte.ai.pojo.tool.ToolClusterIdentity;
import com.arte.ai.pojo.tool.ToolReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;

/**
 * 基于 Redisson Topic 的工具集群事件发布器。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(RedissonClient.class)
@ConditionalOnProperty(prefix = "arte.ai.tool.cluster", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RedisToolClusterEventPublisher implements ToolClusterEventPublisher {

    private final RedissonClient redissonClient;
    private final ToolClusterProperties properties;
    private final ToolClusterIdentity identity;

    @Override
    public void publishProviderChanged(String providerId, ToolClusterEventTypeEnum eventType) {
        publishAfterCommit(newEvent(providerId, null, eventType));
    }

    @Override
    public void publishToolChanged(String providerId, ToolReference reference,
                                   ToolClusterEventTypeEnum eventType) {
        if (reference == null) {
            throw new IllegalArgumentException("reference must not be null");
        }
        publishAfterCommit(newEvent(providerId, reference, eventType));
    }

    private ToolClusterEvent newEvent(String providerId, ToolReference reference,
                                      ToolClusterEventTypeEnum eventType) {
        return new ToolClusterEvent(
                UUID.randomUUID().toString(),
                identity.instanceId(),
                eventType,
                providerId,
                reference == null ? null : reference.namespace(),
                reference == null ? null : reference.name(),
                reference == null ? null : reference.version(),
                Instant.now().toEpochMilli()
        );
    }

    private void publishAfterCommit(ToolClusterEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishNow(event);
                }
            });
            return;
        }
        publishNow(event);
    }

    private void publishNow(ToolClusterEvent event) {
        try {
            redissonClient.getTopic(properties.getTopic()).publish(event);
        } catch (RuntimeException exception) {
            // 数据库仍是权威数据源，广播失败由定时对账补偿，不能回滚已经成功的业务操作。
            log.error("Failed to publish tool cluster event {}, reconciliation will recover it",
                    event.eventId(), exception);
        }
    }
}
