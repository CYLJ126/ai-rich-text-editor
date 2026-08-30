package com.nip.core.utils;

import java.util.HashSet;
import java.util.Set;

/**
 * 删除字符串中的标点符号
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/9/21 22:26 ✾
 */
public class PunctuationUtil {

    /**
     * 去除类型枚举
     */
    public enum RemoveMode {
        PREFIX,  // 去除前缀
        SUFFIX,  // 去除后缀
        ALL      // 全部去除
    }

    /**
     * 去除字符串中的标点符号
     *
     * @param raw          原始字符串
     * @param mode         去除模式
     * @param punctuations 要去除的标点符号
     * @return 处理后的字符串
     */
    public static String removePunctuations(String raw, RemoveMode mode, char[] punctuations) {
        // 参数校验
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        if (punctuations == null || punctuations.length == 0) {
            return raw;
        }
        // 将标点符号数组转换为 Set，提高查找效率
        Set<Character> punctuationSet = new HashSet<>();
        for (char c : punctuations) {
            punctuationSet.add(c);
        }
        return switch (mode) {
            case PREFIX -> removePrefixPunctuations(raw, punctuationSet);
            case SUFFIX -> removeSuffixPunctuations(raw, punctuationSet);
            case ALL -> removeAllPunctuations(raw, punctuationSet);
        };
    }

    /**
     * 去除前缀标点符号
     *
     * @param raw            原始字符串
     * @param punctuationSet 标点符号集合
     * @return 处理后的字符串
     */
    private static String removePrefixPunctuations(String raw, Set<Character> punctuationSet) {
        int start = 0;
        int length = raw.length();
        // 从前往后找到第一个非标点符号的位置
        while (start < length && punctuationSet.contains(raw.charAt(start))) {
            start++;
        }
        return start >= length ? "" : raw.substring(start);
    }

    /**
     * 去除后缀标点符号
     *
     * @param raw            原始字符串
     * @param punctuationSet 标点符号集合
     * @return 处理后的字符串
     */
    private static String removeSuffixPunctuations(String raw, Set<Character> punctuationSet) {
        int end = raw.length() - 1;
        // 从后往前找到第一个非标点符号的位置
        while (end >= 0 && punctuationSet.contains(raw.charAt(end))) {
            end--;
        }
        return end < 0 ? "" : raw.substring(0, end + 1);
    }

    /**
     * 去除所有标点符号
     *
     * @param raw            原始字符串
     * @param punctuationSet 标点符号集合
     * @return 处理后的字符串
     */
    private static String removeAllPunctuations(String raw, Set<Character> punctuationSet) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!punctuationSet.contains(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
