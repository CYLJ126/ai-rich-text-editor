package com.arte.base.spi.cache;

/**
 * 可重建热点缓存。
 *
 * <p>管理按租户、资源版本及适用策略隔离的缓存；缓存不承载唯一业务事实，读取资料仍须授权。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §1.3。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface CacheStore {
}
