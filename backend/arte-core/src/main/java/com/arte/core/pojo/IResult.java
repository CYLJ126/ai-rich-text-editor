package com.arte.core.pojo;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Collection;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 返回结果包装器接口
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/2/7 10:18 ✾
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.CLASS,  // 使用完全限定类名
        include = JsonTypeInfo.As.PROPERTY,  // 作为属性嵌入 JSON
        property = "@class"  // 字段名固定为 @class
)
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
