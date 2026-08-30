package com.arte.core.cache;

import java.util.Collection;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 带数据源的缓存接口
 *
 * @param <K> 缓存键类型
 * @param <V> 缓存值类型
 * @author zhangsc
 * @since 2026/3/5 10:34
 */
public interface CacheableDataSource<K, V> {

    /**
     * 从缓存获取数据，缓存未命中时从数据源加载
     *
     * @param key        缓存键
     * @param dataLoader 数据加载器
     * @return 数据值
     */
    V get(K key, Function<K, V> dataLoader);

    /**
     * 批量从缓存获取数据
     *
     * @param keys       缓存键集合
     * @param dataLoader 批量数据加载器
     * @return 键值对映射
     */
    Map<K, V> multiGet(Collection<K> keys, Function<Collection<K>, Map<K, V>> dataLoader);

    /**
     * 更新数据并刷新缓存
     *
     * @param key         缓存键
     * @param value       新值
     * @param dataUpdater 数据更新器
     */
    void putAndUpdate(K key, V value, BiConsumer<K, V> dataUpdater);

    /**
     * 删除数据并清除缓存
     *
     * @param key         缓存键
     * @param dataDeleter 数据删除器
     */
    void evictAndDelete(K key, Consumer<K> dataDeleter);
}

