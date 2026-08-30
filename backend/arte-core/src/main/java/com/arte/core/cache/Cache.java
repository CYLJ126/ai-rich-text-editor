package com.arte.core.cache;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 通用缓存接口
 *
 * @param <K> 缓存键类型
 * @param <V> 缓存值类型
 * @author zhangsc
 * @since 2026/3/5 10:26
 */
public interface Cache<K, V> {

    /**
     * 获取缓存值
     *
     * @param key 缓存键
     * @return 缓存值，不存在返回null
     */
    V get(K key);

    /**
     * 批量获取缓存值
     *
     * @param keys 缓存键集合
     * @return 键值对映射
     */
    Map<K, V> multiGet(Collection<K> keys);

    /**
     * 设置缓存
     *
     * @param key   缓存键
     * @param value 缓存值
     */
    void put(K key, V value);

    /**
     * 设置缓存（带过期时间）
     *
     * @param key      缓存键
     * @param value    缓存值
     * @param expire   过期时间
     * @param timeUnit 时间单位
     */
    void put(K key, V value, long expire, TimeUnit timeUnit);

    /**
     * 批量设置缓存
     *
     * @param keyValueMap 键值对映射
     */
    void multiPut(Map<K, V> keyValueMap);

    /**
     * 删除缓存
     *
     * @param key 缓存键
     */
    void evict(K key);

    /**
     * 批量删除缓存
     *
     * @param keys 缓存键集合
     */
    void multiEvict(Collection<K> keys);

    /**
     * 按模式删除缓存
     *
     * @param pattern 匹配模式
     */
    void evictByPattern(String pattern);

    /**
     * 清空所有缓存
     */
    void clear();

    /**
     * 检查缓存是否存在
     *
     * @param key 缓存键
     * @return 是否存在
     */
    boolean exists(K key);

    /**
     * 设置过期时间
     *
     * @param key      缓存键
     * @param expire   过期时间
     * @param timeUnit 时间单位
     */
    void expire(K key, long expire, TimeUnit timeUnit);

    /**
     * 获取剩余过期时间
     *
     * @param key 缓存键
     * @return 剩余时间（毫秒），-1表示永不过期，-2表示key不存在
     */
    long getExpire(K key);
}

