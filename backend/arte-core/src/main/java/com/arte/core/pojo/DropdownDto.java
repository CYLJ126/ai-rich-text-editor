package com.arte.core.pojo;

import cn.hutool.core.util.StrUtil;

import java.util.Optional;

/**
 * 下拉选项 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/13 22:50 ✾
 **/
public record DropdownDto(String value, String label, boolean disabled) {

    public static DropdownDto of(Object value, String label) {
        return new DropdownDto(Optional.ofNullable(value).map(Object::toString).orElse(""), StrUtil.nullToEmpty(label), false);
    }

    public static DropdownDto of(Object value, String label, boolean disabled) {
        return new DropdownDto(Optional.ofNullable(value).map(Object::toString).orElse(""), StrUtil.nullToEmpty(label), disabled);
    }
}
