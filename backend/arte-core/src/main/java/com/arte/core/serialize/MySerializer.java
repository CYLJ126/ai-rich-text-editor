package com.arte.core.serialize;

/**
 * 缓存序列化器接口
 *
 * @author zhangsc
 * @since 2026/3/5 10:43
 */
public interface MySerializer<T> {

    /**
     * 序列化对象
     *
     * @param object 待序列化的对象
     * @return 序列化后的字符串
     */
    String serialize(T object);

    /**
     * 反序列化对象
     *
     * @param data 序列化的字符串
     * @return 反序列化后的对象
     */
    T deserialize(String data);

    /**
     * 获取目标类型
     *
     * @return 类型
     */
    Class<T> targetType();
}

