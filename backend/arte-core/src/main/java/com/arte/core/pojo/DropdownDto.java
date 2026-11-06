package com.arte.core.pojo;

import cn.hutool.core.util.StrUtil;

import java.util.Optional;

/**
 * 下拉选项 DTO
 *
 * @param value    选项提交值
 * @param label    选项显示文本
 * @param disabled 是否禁用此选项
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
