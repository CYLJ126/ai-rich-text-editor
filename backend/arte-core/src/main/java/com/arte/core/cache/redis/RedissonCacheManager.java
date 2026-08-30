package com.arte.core.cache.redis;

import com.arte.core.cache.*;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Redisson 的缓存管理器实现
 *
 * @author zhangsc
 * @since 2026/3/5 11:03
 */
@Slf4j
public class RedissonCacheManager extends AbstractCacheManager implements CacheManager {

    private final RedissonClient redissonClient;
    private final ConcurrentHashMap<String, Cache<?, ?>> cacheMap = new ConcurrentHashMap<>();
    private final CacheKeyGenerator keyGenerator;
    private final CacheConfig defaultConfig;

    public RedissonCacheManager(RedissonClient redissonClient,
                                CacheKeyGenerator keyGenerator,
                                CacheConfig defaultConfig) {
        this.redissonClient = redissonClient;
        this.keyGenerator = keyGenerator;
        this.defaultConfig = defaultConfig;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getCache(String cacheName, Class<K> keyType, Class<V> valueType) {
        return (Cache<K, V>) cacheMap.computeIfAbsent(cacheName, name -> createRedissonCache(name, defaultConfig));
    }

    @Override
    public <V> Cache<String, V> getCache(String cacheName, Class<V> valueType) {
        return getCache(cacheName, String.class, valueType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> createCache(String cacheName) {
        Cache<K, V> cache = (Cache<K, V>) createRedissonCache(cacheName, defaultConfig);
        cacheMap.put(cacheName, cache);
        return cache;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> createCache(String cacheName, CacheConfig config) {
        Cache<K, V> cache = (Cache<K, V>) createRedissonCache(cacheName, config);
        cacheMap.put(cacheName, cache);
        return cache;
    }

    private <V> RedissonCache<?, V> createRedissonCache(String cacheName, CacheConfig config) {
        return new RedissonCache<>(redissonClient, cacheName, config, keyGenerator);
    }

    @Override
    public void destroyCache(String cacheName) {
        Cache<?, ?> cache = cacheMap.remove(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }

    @Override
    public Set<String> getCacheNames() {
        return new HashSet<>(cacheMap.keySet());
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        try {
            RKeys keys = redissonClient.getKeys();
            stats.put("totalKeys", keys.count());
            stats.put("cacheNames", getCacheNames());
            stats.put("activeCaches", cacheMap.size());
        } catch (Exception e) {
            log.error("Redisson 缓存统计异常", e);
        }
        return stats;
    }
}

