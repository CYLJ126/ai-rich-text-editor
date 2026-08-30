package com.nip.app.common.enums;

import cn.hutool.core.text.CharSequenceUtil;
import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.nip.core.enums.MyEnum;
import lombok.Getter;

import java.util.Arrays;

/**
 * 新闻类型
 *
 * @author zhangsc
 * @since 2026/6/1 12:49
 */
@JsonFormat(shape = JsonFormat.Shape.OBJECT)
@Getter
public enum WebsiteInfoTypeEnum implements IEnum<String>, MyEnum<String> {
    TECHNOLOGY("technology", "技术"),
    AI("ai", "AI"),
    PRODUCT("product", "产品"),
    FINANCE("finance", "金融"),
    PAYMENT("payment", "支付"),
    GOVERNMENT("government", "政府"),
    ECONOMY("economy", "经济"),
    INSURANCE("insurance", "保险"),
    ALGORITHM("algorithm", "算法"),
    STOCK("stock", "股票"),
    SECURITY("security", "安全"),
    OTHER("other", "其他"),
    ;

    private final String value;
    private final String description;

    WebsiteInfoTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static WebsiteInfoTypeEnum getByLabel(String label) {
        return Arrays.stream(WebsiteInfoTypeEnum.values())
                .filter((type) -> CharSequenceUtil.equals(type.description, label))
                .findFirst().orElse(null);
    }
}
