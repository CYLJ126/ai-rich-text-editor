package com.arte.ai.service.tool.cluster;

import com.arte.ai.config.ToolClusterProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 工具管理写操作的分布式互斥执行器。
 *
 * <p>集群模式下 Redis 锁不可用时拒绝写入，避免多个节点产生分叉状态；未启用集群或测试环境
 * 没有 RedissonClient 时使用进程内锁。</p>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ToolDistributedLockExecutor {

    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final ToolClusterProperties properties;
    private final Map<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    public <T> T execute(String resource, Supplier<T> action) {
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("lock resource must not be blank");
        }
        RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
        if (!properties.isEnabled() || redissonClient == null) {
            return executeLocally(resource, action);
        }
        return executeWithRedis(resource, action, redissonClient);
    }

    public void execute(String resource, Runnable action) {
        execute(resource, () -> {
            action.run();
            return null;
        });
    }

    private <T> T executeWithRedis(String resource, Supplier<T> action, RedissonClient client) {
        String lockName = properties.getManagementLockPrefix() + resource;
        RLock lock = client.getLock(lockName);
        boolean acquired;
        try {
            acquired = lock.tryLock(properties.getManagementLockWaitMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for distributed lock: " + resource,
                    exception);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("distributed tool management lock is unavailable: " + resource,
                    exception);
        }
        if (!acquired) {
            throw new IllegalStateException("tool management operation is busy: " + resource);
        }
        try {
            return action.get();
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (RuntimeException exception) {
                // 业务事务已经结束时，释放锁失败不能篡改原操作结果；Redisson watchdog 会在连接失效后释放锁。
                log.error("Failed to release distributed tool management lock {}", lockName, exception);
            }
        }
    }

    private <T> T executeLocally(String resource, Supplier<T> action) {
        ReentrantLock lock = localLocks.computeIfAbsent(resource, ignored -> new ReentrantLock());
        boolean acquired;
        try {
            acquired = lock.tryLock(properties.getManagementLockWaitMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for local lock: " + resource, exception);
        }
        if (!acquired) {
            throw new IllegalStateException("tool management operation is busy: " + resource);
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
