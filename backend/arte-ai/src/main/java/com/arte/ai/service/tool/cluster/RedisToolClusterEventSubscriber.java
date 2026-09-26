package com.arte.ai.service.tool.cluster;

import com.arte.ai.api.tool.ToolProviderManager;
import com.arte.ai.api.tool.ToolRegistry;
import com.arte.ai.config.ToolClusterProperties;
import com.arte.ai.pojo.tool.ToolClusterEvent;
import com.arte.ai.pojo.tool.ToolClusterIdentity;
import com.arte.ai.pojo.tool.ToolReference;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis 工具目录事件订阅器，将集群变更应用到当前节点的运行时注册表。
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
public class RedisToolClusterEventSubscriber {

    private static final int NOT_SUBSCRIBED = -1;

    private final RedissonClient redissonClient;
    private final ToolClusterProperties properties;
    private final ToolClusterIdentity identity;
    private final ObjectProvider<ToolProviderManager> providerManagerProvider;
    private final ToolRegistry registry;
    private final AtomicInteger listenerId = new AtomicInteger(NOT_SUBSCRIBED);

    @PostConstruct
    public void subscribe() {
        ensureSubscribed();
    }

    @Scheduled(fixedDelayString = "${arte.ai.tool.cluster.subscription-retry-ms:30000}")
    public void ensureSubscribed() {
        if (listenerId.get() != NOT_SUBSCRIBED) {
            return;
        }
        try {
            int registeredId = topic().addListener(ToolClusterEvent.class, this::onMessage);
            if (!listenerId.compareAndSet(NOT_SUBSCRIBED, registeredId)) {
                topic().removeListener(registeredId);
            } else {
                log.info("Subscribed to tool cluster topic {} as instance {}",
                        properties.getTopic(), identity.instanceId());
            }
        } catch (RuntimeException exception) {
            log.error("Failed to subscribe to tool cluster topic {}, will retry",
                    properties.getTopic(), exception);
        }
    }

    @PreDestroy
    public void unsubscribe() {
        int registeredId = listenerId.getAndSet(NOT_SUBSCRIBED);
        if (registeredId == NOT_SUBSCRIBED) {
            return;
        }
        try {
            topic().removeListener(registeredId);
        } catch (RuntimeException exception) {
            log.warn("Failed to remove tool cluster listener {}", registeredId, exception);
        }
    }

    private void onMessage(CharSequence channel, ToolClusterEvent event) {
        if (event == null || identity.instanceId().equals(event.sourceInstanceId())) {
            return;
        }
        try {
            switch (event.eventType()) {
                case PROVIDER_DISABLED -> {
                    registry.unregisterProvider(event.providerId());
                    reconcileProvider(event.providerId(), event.eventId());
                }
                case TOOL_DEPRECATED, TOOL_DISABLED -> {
                    unregisterTool(event);
                    reconcileProvider(event.providerId(), event.eventId());
                }
                case PROVIDER_SYNCED, PROVIDER_ENABLED, TOOL_PUBLISHED, CATALOG_CHANGED ->
                        reconcileProvider(event.providerId(), event.eventId());
            }
        } catch (RuntimeException exception) {
            log.error("Failed to apply tool cluster event {} from channel {}",
                    event.eventId(), channel, exception);
        }
    }

    private void unregisterTool(ToolClusterEvent event) {
        ToolReference reference = event.toolReference();
        if (reference != null) {
            registry.unregister(reference);
        }
    }

    private void reconcileProvider(String providerId, String eventId) {
        ToolProviderManager providerManager = providerManagerProvider.getIfAvailable();
        if (providerManager == null) {
            log.warn("Ignore tool cluster event {} because ToolProviderManager is unavailable", eventId);
            return;
        }
        providerManager.reconcileLocal(providerId).whenComplete((result, throwable) -> {
            if (throwable != null) {
                log.error("Failed to reconcile provider {} for cluster event {}",
                        providerId, eventId, throwable);
            }
        });
    }

    private RTopic topic() {
        return redissonClient.getTopic(properties.getTopic());
    }
}
