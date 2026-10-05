package com.arte.ainew.spi.persistence;

/**
 * 内部耐久快照的受信 Schema 编码器；禁止 Java 原生反序列化、默认类型推断及任意类名。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:11 ✾
 */
public interface ExecutionRecordCodec {
    String encode(Object value);

    <T> T decode(String json, Class<T> expectedType);
}
