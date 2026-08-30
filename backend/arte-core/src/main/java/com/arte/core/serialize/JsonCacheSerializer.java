package com.arte.core.serialize;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON 序列化器实现
 *
 * @author zhangsc
 * @since 2026/3/5 10:45
 */
@Slf4j
public class JsonCacheSerializer<T> implements MySerializer<T> {

    private final ObjectMapper objectMapper;
    private final Class<T> targetType;

    public JsonCacheSerializer(Class<T> targetType) {
        this.targetType = targetType;
        this.objectMapper = SerializerFactory.buildJsonMapperWithoutTypeProperty();
    }

    public JsonCacheSerializer(Class<T> targetType, ObjectMapper objectMapper) {
        this.targetType = targetType;
        this.objectMapper = objectMapper;
    }

    @Override
    public String serialize(T object) {
        if (object == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            log.error("Json 序列化异常：{}", object.getClass().getSimpleName(), e);
            throw new RuntimeException("Json 序列化异常", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public T deserialize(String data) {
        if (data == null || data.trim().isEmpty()) {
            return null;
        }

        try {
            if (targetType == String.class) {
                return (T) data;
            }
            return objectMapper.readValue(data, targetType);
        } catch (Exception e) {
            log.error("Json 反序列化异常：{}-{}", targetType.getSimpleName(), data, e);
            throw new RuntimeException("Json 反序列化异常", e);
        }
    }

    @Override
    public Class<T> targetType() {
        return targetType;
    }
}

