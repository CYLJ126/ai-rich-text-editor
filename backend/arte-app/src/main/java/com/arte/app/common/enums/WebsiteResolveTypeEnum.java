package com.arte.app.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.arte.core.enums.MyEnum;
import lombok.Getter;

/**
 * 新闻列表的解析类型
 * 有的网站可以通过 json 访问新闻列表，有的需要直接解析网页代码，从 html 节点中获取
 * 对应 arte_home_website_info 的 type 字段
 *
 * @author zhangsc
 * @since 2025/4/16 15:20
 */
@Getter
public enum WebsiteResolveTypeEnum implements IEnum<String>, MyEnum<String> {
    JSON("json", "解析 json 格式内容"),
    HTML("html", "解析 html 格式内容"),
    STRING("string", "解析 string 格式内容"),
    ESCAPE_STR("escape", "解析被转义的 string 格式内容");

    private final String value;
    private final String description;

    WebsiteResolveTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }

    @Override
    public String getValue() {
        return this.value;
    }

    public static boolean isJson(WebsiteResolveTypeEnum type) {
        return JSON == type;
    }


    public static boolean isHtml(WebsiteResolveTypeEnum type) {
        return HTML == type;
    }
}
