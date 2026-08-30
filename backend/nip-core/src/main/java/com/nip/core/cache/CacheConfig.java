package com.nip.core.cache;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 缓存配置
 *
 * @author zhangsc
 * @since 2026/3/5 10:31
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CacheConfig {

    /**
     * 默认过期时间（秒）
     */
    private Long defaultExpire;

    /**
     * 最大缓存数量
     */
    private Integer maxSize;

    /**
     * 是否允许空值
     */
    private Boolean allowNullValues = true;

    /**
     * 键前缀
     */
    private String keyPrefix;

    /**
     * 序列化器类型
     */
    private SerializerType serializerType = SerializerType.JSON;

    /**
     * 缓存统计
     */
    private Boolean enableStats = false;

    public enum SerializerType {
        JSON, KRYO, PROTOBUF, JDK
    }
}

