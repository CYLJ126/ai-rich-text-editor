package com.arte.core.utils;

import cn.hutool.extra.pinyin.PinyinUtil;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 中文按拼音排序
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/9/22 18:46 ✾
 */
public class ChineseUtil {

    private ChineseUtil() {
    }

    /**
     * 支持多音字的精确排序
     */
    public static List<String> sortWithToneNumber(List<String> list) {
        return list.stream()
                .sorted((s1, s2) -> {
                    // 带声调数字的拼音
                    String pinyin1 = PinyinUtil.getPinyin(s1, " ");
                    String pinyin2 = PinyinUtil.getPinyin(s2, " ");
                    return pinyin1.compareToIgnoreCase(pinyin2);
                })
                .collect(Collectors.toList());
    }

    /**
     * 首字母相同时按原字符串排序
     */
    public static List<String> sortWithFallback(List<String> list) {
        return list.stream()
                .sorted((s1, s2) -> {
                    String pinyin1 = PinyinUtil.getPinyin(s1, "");
                    String pinyin2 = PinyinUtil.getPinyin(s2, "");
                    int result = pinyin1.compareToIgnoreCase(pinyin2);
                    // 如果拼音相同，按原字符串排序
                    return result != 0 ? result : s1.compareTo(s2);
                })
                .collect(Collectors.toList());
    }
}
