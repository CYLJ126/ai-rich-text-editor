package com.arte.ai.service.tool;

import com.arte.ai.api.tool.ToolRateLimiter;
import com.arte.ai.api.tool.ToolRequest;
import com.arte.ai.config.ToolExecutionProperties;
import com.arte.ai.pojo.tool.ToolInvocation;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Redisson 的集群限流器；没有 Redisson 的单元测试环境回退为进程内固定窗口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Component
@RequiredArgsConstructor
public class RedissonToolRateLimiter implements ToolRateLimiter {

    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final ToolExecutionProperties properties;
    private final Map<String, LocalWindow> localWindows = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(ToolInvocation<? extends ToolRequest> invocation) {
        if (properties.getRateLimitPerMinute() <= 0) {
            return true;
        }
        String key = invocation.context().principal().ownerId() + ":" + invocation.tool();
        RedissonClient client = redissonClientProvider.getIfAvailable();
        if (client != null) {
            var limiter = client.getRateLimiter(properties.getRateLimitKeyPrefix() + key);
            limiter.trySetRate(RateType.OVERALL, properties.getRateLimitPerMinute(),
                    1, RateIntervalUnit.MINUTES);
            return limiter.tryAcquire();
        }
        long minute = System.currentTimeMillis() / 60_000L;
        LocalWindow window = localWindows.compute(key, (ignored, current) ->
                current == null || current.minute() != minute
                        ? new LocalWindow(minute, new AtomicLong()) : current);
        return window.count().incrementAndGet() <= properties.getRateLimitPerMinute();
    }

    private record LocalWindow(long minute, AtomicLong count) {
    }
}
