package com.arte.core.cache;

import lombok.extern.slf4j.Slf4j;

/**
 * 缓存键生成器
 *
 * @author zhangsc
 * @since 2026/3/5 10:50
 */
@Slf4j
public class CacheKeyGenerator {

    private static final String SEPARATOR = ":";
    private static final String DEFAULT_PREFIX = "cache";

    /**
     * 生成缓存键
     *
     * @param cacheName 缓存名称
     * @param key       原始键
     * @param prefix    前缀
     * @return 生成的缓存键
     */
    public String generateKey(String cacheName, Object key, String prefix) {
        StringBuilder keyBuilder = new StringBuilder();
        // 添加前缀
        if (prefix != null && !prefix.trim().isEmpty()) {
            keyBuilder.append(prefix);
        } else {
            keyBuilder.append(DEFAULT_PREFIX);
        }
        // 添加缓存名称
        if (cacheName != null && !cacheName.trim().isEmpty()) {
            keyBuilder.append(SEPARATOR).append(cacheName);
        }
        // 添加具体键
        if (key != null) {
            keyBuilder.append(SEPARATOR).append(key);
        }
        String finalKey = keyBuilder.toString();
        log.debug("cacheName：{}，key：{}，prefix：{}，生成缓存键：{}", cacheName, key, prefix, finalKey);
        return finalKey;
    }
}

