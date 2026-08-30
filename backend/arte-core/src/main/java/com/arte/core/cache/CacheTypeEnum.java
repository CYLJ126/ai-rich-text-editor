package com.arte.core.cache;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.arte.core.enums.MyEnum;
import lombok.Getter;

/**
 * 缓存键
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/3/5 18:04 ✾
 */
@Getter
public enum CacheTypeEnum implements IEnum<String>, MyEnum<String> {
    USER("user", "用户"),
    MENU("menu", "菜单"),
    ROLE("role", "角色"),
    MENU_OPERATION("menuOperation", "菜单操作"),
    DEFAULT_MODEL_CONFIG("defaultModelConfig", "默认模型配置");

    private final String value;
    private final String cacheName;
    private final String description;

    CacheTypeEnum(String value, String description) {
        this.value = value;
        this.cacheName = value + "Cache";
        this.description = description;
    }
}
