package com.arte.core.cache;

public record CacheTypeRecord(CacheTypeEnum cacheType, Class<?> cacheKeyType, Class<?> cacheValueType) {
}
