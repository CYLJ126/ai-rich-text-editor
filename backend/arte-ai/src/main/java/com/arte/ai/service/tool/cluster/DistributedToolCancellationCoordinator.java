package com.arte.ai.service.tool.cluster;

import com.arte.ai.api.tool.ToolCancellation;
import com.arte.ai.config.ToolClusterProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通过 Redisson Topic 广播取消，并在当前执行节点触发具体工具的取消回调。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DistributedToolCancellationCoordinator {
    private static final long REDIS_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private final ObjectProvider<RedissonClient> clients;
    private final ToolClusterProperties properties;
    private final ConcurrentMap<String, Signal> signals = new ConcurrentHashMap<>();
    private volatile Integer listenerId;

    @PostConstruct
    public void subscribe() {
        RedissonClient client = clients.getIfAvailable();
        if (client != null && properties.isEnabled()) {
            try {
                listenerId = client.getTopic(topic()).addListener(String.class,
                        (channel, callId) -> signal(callId));
            } catch (RuntimeException exception) {
                log.warn("Unable to subscribe to distributed tool cancellation topic", exception);
            }
        }
    }

    public ToolCancellation token(String callId, ToolCancellation upstream) {
        Signal signal = signals.computeIfAbsent(callId, ignored -> new Signal());
        return new ToolCancellation() {
            @Override
            public boolean isCancellationRequested() {
                return upstream.isCancellationRequested() || cancelled(callId, signal);
            }

            @Override
            public void onCancellation(Runnable callback) {
                AtomicBoolean invoked = new AtomicBoolean();
                Runnable once = () -> {
                    if (invoked.compareAndSet(false, true)) safeRun(callback);
                };
                upstream.onCancellation(once);
                signal.callbacks.add(once);
                if (isCancellationRequested()) once.run();
            }
        };
    }

    public boolean cancel(String callId) {
        signal(callId);
        RedissonClient client = clients.getIfAvailable();
        if (client != null && properties.isEnabled()) {
            try {
                client.getBucket(key(callId)).set(Boolean.TRUE, 1, TimeUnit.HOURS);
                client.getTopic(topic()).publish(callId);
            } catch (RuntimeException exception) {
                log.warn("Unable to publish distributed cancellation for call {}", callId, exception);
            }
        }
        return true;
    }

    public void clear(String callId) {
        signals.remove(callId);
    }

    private void signal(String callId) {
        Signal signal = signals.computeIfAbsent(callId, ignored -> new Signal());
        if (signal.cancelled.compareAndSet(false, true)) signal.callbacks.forEach(this::safeRun);
    }

    private boolean cancelled(String callId, Signal signal) {
        if (signal.cancelled.get()) return true;
        long now = System.nanoTime();
        long previous = signal.lastRedisCheck.get();
        if (now - previous < REDIS_POLL_NANOS
                || !signal.lastRedisCheck.compareAndSet(previous, now)) return false;
        RedissonClient client = clients.getIfAvailable();
        if (client == null || !properties.isEnabled()) return false;
        try {
            if (Boolean.TRUE.equals(client.<Boolean>getBucket(key(callId)).get())) {
                signal(callId);
                return true;
            }
        } catch (RuntimeException exception) {
            log.debug("Unable to poll distributed cancellation for call {}", callId, exception);
        }
        return false;
    }

    private void safeRun(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
        }
    }

    private String topic() {
        return properties.getTopic() + ":cancellations";
    }

    private String key(String callId) {
        return properties.getManagementLockPrefix() + "cancel:" + callId;
    }

    @PreDestroy
    public void close() {
        RedissonClient client = clients.getIfAvailable();
        if (client != null && listenerId != null) {
            try {
                client.getTopic(topic()).removeListener(listenerId);
            } catch (RuntimeException exception) {
                log.debug("Unable to remove tool cancellation listener", exception);
            }
        }
    }

    private static final class Signal {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicLong lastRedisCheck = new AtomicLong();
        private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();
    }
}
