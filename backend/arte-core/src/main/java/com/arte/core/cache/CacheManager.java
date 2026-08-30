package com.arte.core.cache;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 缓存管理器接口
 *
 * @author zhangsc
 * @since 2026/3/5 10:31
 */
public interface CacheManager {

    /**
     * 获取指定名称的缓存
     *
     * @param cacheName 缓存名称
     * @param keyType   键类型
     * @param valueType 值类型
     * @return 缓存实例
     */
    <K, V> Cache<K, V> getCache(String cacheName, Class<K> keyType, Class<V> valueType);

    /**
     * 获取指定名称的缓存（默认String类型的key）
     *
     * @param cacheName 缓存名称
     * @param valueType 值类型
     * @return 缓存实例
     */
    <V> Cache<String, V> getCache(String cacheName, Class<V> valueType);

    /**
     * 创建缓存，使用默认缓存配置
     *
     * @param cacheName 缓存名称
     * @return 缓存实例
     */
    <K, V> Cache<K, V> createCache(String cacheName);

    /**
     * 创建缓存
     *
     * @param cacheName 缓存名称
     * @param config    缓存配置
     * @return 缓存实例
     */
    <K, V> Cache<K, V> createCache(String cacheName, CacheConfig config);

    /**
     * 销毁缓存
     *
     * @param cacheName 缓存名称
     */
    void destroyCache(String cacheName);

    /**
     * 获取所有缓存名称
     *
     * @return 缓存名称集合
     */
    Set<String> getCacheNames();

    /**
     * 获取缓存统计信息
     *
     * @return 缓存统计
     */
    default Map<String, Object> getStats() {
        return Collections.emptyMap();
    }

    /**
     * 注册各类型缓存
     *
     * @param cacheTypeRecords 缓存类型记录集合
     */
    default void registerCache(Collection<CacheTypeRecord> cacheTypeRecords) {
    }

    /**
     * 注册各类型缓存
     *
     * @param annotatedClasses 缓存类型注解
     */
    default void registerCachesFromAnnotatedClasses(Collection<Class<?>> annotatedClasses) {
    }

    /**
     * 注册各类型缓存
     *
     * @param basePackages 扫描缓存类型注解的基础包，多个包用逗号分隔
     */
    default void registerCachesFromAnnotationScan(String basePackages) {
    }
}

