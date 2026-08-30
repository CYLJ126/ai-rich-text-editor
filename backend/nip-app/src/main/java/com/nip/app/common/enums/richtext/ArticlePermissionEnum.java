package com.nip.app.common.enums.richtext;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.nip.core.enums.MyEnum;
import lombok.Getter;

@Getter
public enum ArticlePermissionEnum implements IEnum<String>, MyEnum<String> {
    READ("READ", "可读", 10),
    COMMENT("COMMENT", "可批注", 20),
    READ_WRITE("READ_WRITE", "可编辑", 30),
    FULL_CONTROL("FULL_CONTROL", "完全控制", 40);

    private final String value;
    private final String description;
    private final int level;

    ArticlePermissionEnum(String value, String description, int level) {
        this.value = value;
        this.description = description;
        this.level = level;
    }

    public static ArticlePermissionEnum of(String value) {
        for (ArticlePermissionEnum item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        return READ;
    }

    public static boolean isValid(String value) {
        for (ArticlePermissionEnum item : values()) {
            if (item.value.equals(value)) {
                return true;
            }
        }
        return false;
    }

    public static String higher(String first, String second) {
        ArticlePermissionEnum left = of(first);
        ArticlePermissionEnum right = of(second);
        return left.level >= right.level ? left.value : right.value;
    }

    public boolean canWrite() {
        return this == READ_WRITE || this == FULL_CONTROL;
    }

    public boolean canComment() {
        return this.level >= COMMENT.level;
    }

    public boolean canDeleteOrGrant() {
        return this == FULL_CONTROL;
    }
}
