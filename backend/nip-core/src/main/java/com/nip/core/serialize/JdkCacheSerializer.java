package com.nip.core.serialize;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Base64;

/**
 * JDK 序列化器实现
 *
 * @author zhangsc
 * @since 2026/3/5 10:47
 */
@Slf4j
public record JdkCacheSerializer<T>(Class<T> targetType) implements MySerializer<T> {

    @Override
    public String serialize(T object) {
        if (object == null) {
            return null;
        }

        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(object);
            oos.flush();
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            log.error("JDK serializer 序列化异常", e);
            throw new RuntimeException("JDK serializer 序列化异常", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public T deserialize(String data) {
        if (data == null || data.trim().isEmpty()) {
            return null;
        }

        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
            ObjectInputStream ois = new ObjectInputStream(bis);
            return (T) ois.readObject();
        } catch (Exception e) {
            log.error("JDK serializer 反序列化异常", e);
            throw new RuntimeException("JDK serializer 反序列化异常", e);
        }
    }
}

