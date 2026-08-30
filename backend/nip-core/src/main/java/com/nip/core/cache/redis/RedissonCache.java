package com.nip.core.cache.redis;

import com.nip.core.cache.Cache;
import com.nip.core.cache.CacheConfig;
import com.nip.core.cache.CacheKeyGenerator;
import com.nip.core.cache.CacheableDataSource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Redis 缓存实现
 * 不用缓存配置的序列化器，而是使用 Redisson 提供的序列化器
 *
 * @author zhangsc
 * @since 2026/3/5 10:36
 */
@Slf4j
public class RedissonCache<K, V> implements Cache<K, V>, CacheableDataSource<K, V> {

    private final RedissonClient redissonClient;
    private final CacheConfig config;
    private final String cacheName;
    private final CacheKeyGenerator keyGenerator;

    // 防止缓存击穿的分布式锁缓存
    private final ConcurrentHashMap<String, RLock> lockCache = new ConcurrentHashMap<>();

    public RedissonCache(RedissonClient redissonClient,
                         String cacheName,
                         CacheConfig config,
                         CacheKeyGenerator keyGenerator) {
        this.redissonClient = redissonClient;
        this.cacheName = cacheName;
        this.config = config;
        this.keyGenerator = keyGenerator;
    }

    @Override
    public V get(K key) {
        String redisKey = generateRedisKey(key);
        try {
            RBucket<V> bucket = redissonClient.getBucket(redisKey);
            return bucket.get();
        } catch (Exception e) {
            log.error("Redisson 获取缓存异常，key：{}", redisKey, e);
            return null;
        }
    }

    @Override
    public Map<K, V> multiGet(Collection<K> keys) {
        if (keys == null || keys.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, K> redisKeyToOriginalKey = new HashMap<>();
        for (K key : keys) {
            String redisKey = generateRedisKey(key);
            redisKeyToOriginalKey.put(redisKey, key);
        }
        try {
            RBuckets buckets = redissonClient.getBuckets();
            Map<String, V> values = buckets.get(redisKeyToOriginalKey.keySet().toArray(new String[0]));

            Map<K, V> result = new HashMap<>();
            values.forEach((redisKey, value) -> {
                if (value != null) {
                    try {
                        K originalKey = redisKeyToOriginalKey.get(redisKey);
                        result.put(originalKey, value);
                    } catch (Exception e) {
                        log.error("Redisson 批量获取缓存异常，key：{}", redisKey, e);
                    }
                }
            });

            return result;
        } catch (Exception e) {
            log.error("Redisson 批量获取异常", e);
            return new HashMap<>();
        }
    }

    @Override
    public void put(K key, V value) {
        if (value == null && !config.getAllowNullValues()) {
            return;
        }

        String redisKey = generateRedisKey(key);
        try {
            RBucket<V> bucket = redissonClient.getBucket(redisKey);
            if (config.getDefaultExpire() != null && config.getDefaultExpire() > 0) {
                bucket.set(value, Duration.ofSeconds(config.getDefaultExpire()));
            } else {
                bucket.set(value);
            }
        } catch (Exception e) {
            log.error("Redisson 设置缓存异常，key：{}", redisKey, e);
        }
    }

    @Override
    public void put(K key, V value, long expire, TimeUnit timeUnit) {
        if (value == null && !config.getAllowNullValues()) {
            return;
        }
        String redisKey = generateRedisKey(key);
        try {
            RBucket<V> bucket = redissonClient.getBucket(redisKey);
            bucket.set(value, Duration.of(expire, timeUnit.toChronoUnit()));
        } catch (Exception e) {
            log.error("Redisson 带过期时间设置缓存异常, key：{}", redisKey, e);
        }
    }

    @Override
    public void multiPut(Map<K, V> keyValueMap) {
        if (keyValueMap == null || keyValueMap.isEmpty()) {
            return;
        }
        try {
            Map<String, V> redisMap = new HashMap<>();
            keyValueMap.forEach((key, value) -> {
                if (value != null || config.getAllowNullValues()) {
                    try {
                        String redisKey = generateRedisKey(key);
                        redisMap.put(redisKey, value);
                    } catch (Exception e) {
                        log.error("批量设置缓存异常，key：{}", key, e);
                    }
                }
            });
            if (!redisMap.isEmpty()) {
                RBuckets buckets = redissonClient.getBuckets();
                buckets.set(redisMap);
                // 设置过期时间（如果配置了的话）
                if (config.getDefaultExpire() != null && config.getDefaultExpire() > 0) {
                    Duration expireDuration = Duration.ofSeconds(config.getDefaultExpire());
                    redisMap.keySet().forEach(redisKey -> {
                        try {
                            redissonClient.getBucket(redisKey).expire(expireDuration);
                        } catch (Exception e) {
                            log.error("Redisson 设置缓存过期时间异常，key：{}", redisKey, e);
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.error("Redisson 批量设置缓存异常", e);
        }
    }

    @Override
    public void evict(K key) {
        String redisKey = generateRedisKey(key);
        try {
            RBucket<String> bucket = redissonClient.getBucket(redisKey);
            bucket.delete();
        } catch (Exception e) {
            log.error("Redisson 删除缓存异常, key: {}", redisKey, e);
        }
    }

    @Override
    public void multiEvict(Collection<K> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }

        try {
            String[] redisKeys = keys.stream()
                    .map(this::generateRedisKey)
                    .toArray(String[]::new);
            redissonClient.getKeys().delete(redisKeys);
        } catch (Exception e) {
            log.error("Redisson 批量删除缓存异常", e);
        }
    }

    @Override
    public void evictByPattern(String pattern) {
        String fullPattern = generateRedisKey(pattern);
        try {
            RKeys keys = redissonClient.getKeys();
            keys.deleteByPattern(fullPattern);
        } catch (Exception e) {
            log.error("Redisson 模式匹配删除缓存异常, pattern：{}", fullPattern, e);
        }
    }

    @Override
    public void clear() {
        String pattern = generateRedisKey("*");
        try {
            RKeys keys = redissonClient.getKeys();
            keys.deleteByPattern(pattern);
        } catch (Exception e) {
            log.error("Redisson 清空缓存异常", e);
        }
    }

    @Override
    public boolean exists(K key) {
        String redisKey = generateRedisKey(key);
        try {
            RBucket<String> bucket = redissonClient.getBucket(redisKey);
            return bucket.isExists();
        } catch (Exception e) {
            log.error("Redisson 判断缓存是否存在异常, key：{}", redisKey, e);
            return false;
        }
    }

    @Override
    public void expire(K key, long expire, TimeUnit timeUnit) {
        String redisKey = generateRedisKey(key);
        try {
            RBucket<String> bucket = redissonClient.getBucket(redisKey);
            bucket.expire(Duration.of(expire, timeUnit.toChronoUnit()));
        } catch (Exception e) {
            log.error("Redisson 设置缓存过期时间异常，key：{}", redisKey, e);
        }
    }

    @Override
    public long getExpire(K key) {
        String redisKey = generateRedisKey(key);
        try {
            RBucket<String> bucket = redissonClient.getBucket(redisKey);
            return bucket.remainTimeToLive();
        } catch (Exception e) {
            log.error("Redisson 获取缓存过期时间异常，key：{}", redisKey, e);
            return -2; // key 不存在
        }
    }

    /**
     * 防止缓存击穿的 get 方法（使用分布式锁）
     */
    @Override
    public V get(K key, Function<K, V> dataLoader) {
        // 先尝试从缓存获取
        V cachedValue = get(key);
        if (cachedValue != null) {
            return cachedValue;
        }
        String redisKey = generateRedisKey(key);
        String lockKey = "lock:" + redisKey;
        // 使用分布式锁防止缓存击穿
        RLock lock = lockCache.computeIfAbsent(lockKey, redissonClient::getLock);
        try {
            // 尝试获取锁，最多等待10秒，锁自动过期时间30秒
            if (lock.tryLock(10, 30, TimeUnit.SECONDS)) {
                try {
                    // 双重检查，可能其他线程已经加载了数据
                    cachedValue = get(key);
                    if (cachedValue != null) {
                        return cachedValue;
                    }

                    // 从数据源加载数据
                    V value = dataLoader.apply(key);

                    // 更新缓存（允许空值缓存，防止缓存穿透）
                    if (value != null || config.getAllowNullValues()) {
                        put(key, value);
                    }

                    return value;
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } else {
                log.warn("获取锁失败，key：{}，将直接从数据源获取", key);
                // 如果获取锁失败，直接从数据源加载（降级处理）
                return dataLoader.apply(key);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("等待锁时发生中断异常，key：{}", key, e);
            return dataLoader.apply(key);
        } catch (Exception e) {
            log.error("带数据源获取缓存时异常，key：{}", key, e);
            return dataLoader.apply(key);
        } finally {
            // 清理本地锁缓存，避免内存泄漏
            lockCache.remove(lockKey);
        }
    }

    @Override
    public Map<K, V> multiGet(Collection<K> keys, Function<Collection<K>, Map<K, V>> dataLoader) {
        if (keys == null || keys.isEmpty()) {
            return new HashMap<>();
        }
        // 先从缓存批量获取
        Map<K, V> cachedResults = multiGet(keys);
        // 找出缓存未命中的keys
        Set<K> missedKeys = keys.stream()
                .filter(key -> !cachedResults.containsKey(key))
                .collect(Collectors.toSet());
        if (missedKeys.isEmpty()) {
            return cachedResults;
        }
        // 批量加载未命中的数据
        try {
            Map<K, V> loadedData = dataLoader.apply(missedKeys);
            // 批量更新缓存
            if (!loadedData.isEmpty()) {
                multiPut(loadedData);
                cachedResults.putAll(loadedData);
            }
            // 处理空值缓存（防止缓存穿透）
            if (config.getAllowNullValues()) {
                for (K missedKey : missedKeys) {
                    if (!loadedData.containsKey(missedKey)) {
                        put(missedKey, null);
                        cachedResults.put(missedKey, null);
                    }
                }
            }
        } catch (Exception e) {
            log.error("批量获取缓存异常", e);
        }
        return cachedResults;
    }

    @Override
    public void putAndUpdate(K key, V value, BiConsumer<K, V> dataUpdater) {
        try {
            // 更新数据源
            dataUpdater.accept(key, value);
            // 更新缓存
            put(key, value);
        } catch (Exception e) {
            log.error("putAndUpdate 缓存时异常，key: {}", key, e);
            // 如果数据源更新失败，不更新缓存
            throw new RuntimeException("更新缓存异常", e);
        }
    }

    @Override
    public void evictAndDelete(K key, Consumer<K> dataDeleter) {
        try {
            // 删除数据源数据
            dataDeleter.accept(key);
            // 删除缓存
            evict(key);
        } catch (Exception e) {
            log.error("evictAndDelete 缓存时异常, key：{}", key, e);
            throw new RuntimeException("删除缓存异常", e);
        }
    }

    /**
     * 生成 Redis 键
     */
    private String generateRedisKey(Object key) {
        return keyGenerator.generateKey(cacheName, key, config.getKeyPrefix());
    }
}