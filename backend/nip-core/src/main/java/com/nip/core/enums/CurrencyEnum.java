package com.nip.core.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 货币
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/17 17:21 ✾
 */
@Getter
public enum CurrencyEnum implements IEnum<String>, MyEnum<String> {
    CNY("CNY", "人民币"),
    USD("USD", "美元"),
    EUR("EUR", "欧元"),
    GBP("GBP", "英镑"),
    ;

    private final String value;

    private final String description;

    CurrencyEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
