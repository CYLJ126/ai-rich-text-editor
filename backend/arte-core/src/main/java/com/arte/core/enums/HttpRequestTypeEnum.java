package com.arte.core.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * http 请求类型
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/5 21:21 ✾
 */
@Getter
public enum HttpRequestTypeEnum implements IEnum<String>, MyEnum<String> {
    POST("POST", "POST"),
    GET("GET", "GET"),
    ;

    private final String value;
    private final String description;

    HttpRequestTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }

    @Override
    public String getValue() {
        return this.value;
    }

    public static boolean isPost(HttpRequestTypeEnum type) {
        return POST == type;
    }

    public static boolean isGet(HttpRequestTypeEnum type) {
        return GET == type;
    }
}
