package com.arte.app.common.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Token 数量估算工具
 * 修复：splitByTokens 中消除 substring 导致的大对象分配
 */
public final class TokenCountUtil {
    public static final int MAX_TOKENS = 512;
    public static final int OVERLAP_TOKENS = 50;

    private TokenCountUtil() {
    }

    /**
     * 估算文本 token 数（不创建 char[] 数组）
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        return estimateRange(text, 0, text.length());
    }

    /**
     * 估算 [start, end) 范围内的 token 数，零 substring 分配
     */
    public static int estimateRange(String text, int start, int end) {
        if (text == null || start >= end) return 0;
        int tokenCount = 0;
        int asciiRun = 0;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                if (asciiRun > 0) {
                    tokenCount += Math.max(1, (asciiRun + 3) / 4);
                    asciiRun = 0;
                }
                tokenCount++;
            } else if (Character.isLetterOrDigit(c)) {
                asciiRun++;
            } else {
                if (asciiRun > 0) {
                    tokenCount += Math.max(1, (asciiRun + 3) / 4);
                    asciiRun = 0;
                }
                if (!Character.isWhitespace(c)) {
                    tokenCount++;
                }
            }
        }
        if (asciiRun > 0) {
            tokenCount += Math.max(1, (asciiRun + 3) / 4);
        }
        return Math.max(1, tokenCount);
    }

    /**
     * 将长文本按 maxTokens 切分为多个片段（无重叠）。
     * 消除 estimate(text.substring(...)) 的大对象分配，使用 estimateRange 在原字符串上直接计算。
     *
     * @param text      原始文本
     * @param maxTokens 每段最大 token 数
     * @return 切分后的文本片段列表（至少含1个元素）
     */
    public static List<String> splitByTokens(String text, int maxTokens) {
        List<String> segments = new ArrayList<>();
        if (text == null || text.isEmpty()) return segments;

        int len = text.length();
        int segStart = 0;
        int tokenCount = 0;
        int asciiRun = 0;

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            int delta = 0;

            if (isCjk(c)) {
                if (asciiRun > 0) {
                    delta += Math.max(1, (asciiRun + 3) / 4);
                    asciiRun = 0;
                }
                delta++;
            } else if (Character.isLetterOrDigit(c)) {
                asciiRun++;
                // asciiRun 累计，delta 暂不计入
            } else {
                if (asciiRun > 0) {
                    delta += Math.max(1, (asciiRun + 3) / 4);
                    asciiRun = 0;
                }
                if (!Character.isWhitespace(c)) {
                    delta++;
                }
            }
            tokenCount += delta;

            if (tokenCount >= maxTokens) {
                // 尝试在换行处截断，避免切断句子
                int cutAt = findBreakPoint(text, segStart, i + 1);
                // 仅在真正需要时 substring，且保证 cutAt > segStart
                String seg = text.substring(segStart, cutAt).strip();
                if (!seg.isEmpty()) segments.add(seg);
                segStart = cutAt;
                // 用 estimateRange 替代 estimate(text.substring(...))，零额外分配
                tokenCount = estimateRange(text, segStart, i + 1);
                asciiRun = 0;
            }
        }

        // 末尾剩余
        if (segStart < len) {
            String tail = text.substring(segStart).strip();
            if (!tail.isEmpty()) segments.add(tail);
        }
        return segments.isEmpty() ? List.of(text) : segments;
    }

    /**
     * 在 [start, end) 范围内从 end 往前找最近的换行符位置，
     * 找不到则直接返回 end（硬切）
     */
    private static int findBreakPoint(String text, int start, int end) {
        for (int i = end - 1; i > start; i--) {
            if (text.charAt(i) == '\n') return i + 1;
        }
        return end;
    }

    public static boolean isCjk(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF')
                || (c >= '\u3400' && c <= '\u4DBF')
                || (c >= '\uF900' && c <= '\uFAFF')
                || (c >= '\u3000' && c <= '\u303F')
                || (c >= '\uFF65' && c <= '\uFF9F');
    }
}