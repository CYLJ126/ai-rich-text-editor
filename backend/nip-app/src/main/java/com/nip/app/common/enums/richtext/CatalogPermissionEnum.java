package com.nip.app.common.enums.richtext;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.nip.core.enums.MyEnum;
import lombok.Getter;

@Getter
public enum CatalogPermissionEnum implements IEnum<String>, MyEnum<String> {
    ACCESS("ACCESS", "可访问", 10),
    CREATE_CHILD("CREATE_CHILD", "可新建子内容", 20),
    FULL_CONTROL("FULL_CONTROL", "完全控制", 30);

    private final String value;
    private final String description;
    private final int level;

    CatalogPermissionEnum(String value, String description, int level) {
        this.value = value;
        this.description = description;
        this.level = level;
    }

    public static CatalogPermissionEnum of(String value) {
        for (CatalogPermissionEnum item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        return ACCESS;
    }

    public static boolean isValid(String value) {
        for (CatalogPermissionEnum item : values()) {
            if (item.value.equals(value)) {
                return true;
            }
        }
        return false;
    }

    public static String higher(String first, String second) {
        CatalogPermissionEnum left = of(first);
        CatalogPermissionEnum right = of(second);
        return left.level >= right.level ? left.value : right.value;
    }

    public boolean canCreateChild() {
        return this == CREATE_CHILD || this == FULL_CONTROL;
    }

    public boolean canDeleteOrGrant() {
        return this == FULL_CONTROL;
    }
}
