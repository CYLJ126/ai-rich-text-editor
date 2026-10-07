package com.arte.core.pojo;

import java.util.Collection;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 返回结果包装器接口
 * <p>
 * 类型信息由序列化器配置决定，HTTP 响应不携带 Java 类名；
 * 需要恢复运行时类型的缓存使用启用类型信息的序列化器。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/2/7 10:18 ✾
 */
public interface IResult<T> {

    String BUSINESS_SUCCESS_LABEL = "操作成功";

    String BUSINESS_FAIL_LABEL = "操作失败";

    String getCode();

    String getDesc();

    Boolean getSuccess();

    IResult<T> setCode(String code);

    IResult<T> setDesc(String desc);

    IResult<T> setSuccess(Boolean success);

    default boolean isSuccess() {
        return Boolean.TRUE.equals(getSuccess());
    }

    default T getData() {
        return null;
    }

    default Stream<T> stream() {
        return getRecords() == null ? Stream.empty() : getRecords().stream();
    }

    default Collection<T> getRecords() {
        return null;
    }


    default boolean isEmpty() {
        return getRecords() == null || getRecords().isEmpty();
    }

    default void forEach(Consumer<? super T> action) {
        if (isEmpty()) {
            return;
        }
        getRecords().forEach(action);
    }
}
