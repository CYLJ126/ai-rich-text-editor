package com.nip.core.utils;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Pair;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.pinyin.PinyinUtil;
import com.nip.core.enums.MyTimeUnit;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/9/15 21:43 ✾
 */
public class FormatUtil {

    private FormatUtil() {
    }

    /**
     * 为无序号的文本添加序号
     *
     * @param text 无序号的文本
     * @return 添加序号后的文本
     */
    public static String formatSerialNo(String text) {
        String[] lines = text.split("\n");
        if (lines.length > 0 && lines[0].trim().startsWith("1.")) {
            return text; // 如果已有序号，返回原文本
        }
        StringBuilder result = new StringBuilder();
        List<Integer> countStack = new ArrayList<>();
        countStack.add(0);
        for (String line : lines) {
            // 判断 line是空行，则跳过
            if (line.trim().isEmpty()) {
                continue;
            }
            int level = countLeadingSpaces(line);
            updateCounter(countStack, level);
            result.append("\t".repeat(Math.max(0, level)));
            result.append(countStack.get(level)).append(". ").append(line.trim());
            // 添加换行符
            result.append("\n");
        }
        return result.toString().trim();
    }

    /**
     * 去除有序号文本中的序号
     *
     * @param text 有序号文本
     * @return 无序号文本
     */
    public static String removeSerialNo(String text) {
        if (!StrUtil.startWith(text, "1.")) {
            return text; // 如果没有序号，不需要移除，直接返回
        }
        String[] lines = text.split("\n");
        StringBuilder result = new StringBuilder();

        for (String line : lines) {
            // 以前导空字符（空格或 tab）计算该行是第几层，最小为 0 层
            int level = countLeadingSpaces(line);
            String cleanLine = line.replaceFirst("^\\s*(\\d+\\.)?\\s*", "");
            String restoredLine = "\t".repeat(level) + cleanLine;
            result.append(restoredLine).append("\n");
        }

        return result.toString().trim();
    }

    /**
     * 待删除前缀标点符号
     */
    private static final char[] PREFIX_REMOVE_CHARS = {'#', '：'};
    /**
     * 待删除后缀标点符号
     */
    private static final char[] SUFFIX_REMOVE_CHARS = {'#', ':', '：', ',', '，', ';', '；', '.', '。', '?', '？', '!', '！'};

    /**
     * 格式化总结内容，删除多余的标点符号，添加序号，提取时间等
     *
     * @param raw 总结内容
     * @return 格式化后的总结内容
     */
    public static String formatSummary(String raw) {
        raw = StrUtil.trim(raw);
        if (StrUtil.isEmpty(raw)) {
            return StrUtil.EMPTY;
        }
        ContentNode root = ContentNode.virtualRoot();
        String[] lines = raw.split("\r?\n");
        Stack<ContentNode> fatherStack = new Stack<>();
        fatherStack.push(root);
        for (String line : lines) {
            int indentSpaces = countLeadingSpaces(line);
            line = StrUtil.trim(line);
            if (StrUtil.isEmpty(line)) {
                // 跳过空行
                continue;
            }
            // 提取时间，以分钟为单位
            Pair<String, Double> pair = TimeExtractUtil.extractLineTime(line, MyTimeUnit.MINUTE);
            // 去除前缀
            line = PunctuationUtil.removePunctuations(pair.getKey(), PunctuationUtil.RemoveMode.PREFIX, PREFIX_REMOVE_CHARS);
            // 去除后缀
            line = PunctuationUtil.removePunctuations(line, PunctuationUtil.RemoveMode.SUFFIX, SUFFIX_REMOVE_CHARS);
            // root 节点的 level 为 0，下一级从 1 开始计数（即 0 个空格为第 1 级）
            ContentNode node = new ContentNode(indentSpaces + 1, line, (int) Math.round(pair.getValue()));
            ContentNode father = fatherStack.peek();
            // 找到父级，并添加到其子级列表中；因为栈中初始为 root 节点，所以永远不会弹出 root 节点
            while (node.level <= father.level) {
                father = fatherStack.pop();
                if (fatherStack.peek().level < node.level) {
                    father = fatherStack.peek();
                    break;
                }
            }
            father.addChild(node);
            node.index = father.children.size();
            fatherStack.push(node);
        }
        StringBuilder sb = new StringBuilder();
        AtomicInteger totalTime = new AtomicInteger();
        ContentNode.mergeAndResort(root);
        ContentNode.flatten(root).forEach(node -> {
            totalTime.addAndGet(node.time);
            sb.append("\t".repeat(node.level - 1)).append(node.index).append(". ").append(node.content).append(formatTime(node.time)).append('；')
                    .append(System.lineSeparator());
        });
        return formatTime(totalTime.get()) + System.lineSeparator() + sb.toString().trim();
    }

    /**
     * 去除多行文本中的子级内容，层级从 1 级开始
     *
     * @param raw   原始文本
     * @param level 层级，大于等于该层级的子级内容将被去除
     * @return 去除子级内容后的文本
     */
    public static String removeSub(String raw, int level) {
        raw = StrUtil.trim(raw);
        if (StrUtil.isEmpty(raw)) {
            return StrUtil.EMPTY;
        }
        String[] lines = raw.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            int indentSpaces = countLeadingSpaces(line);
            // 0 个空格表示第 1 级
            if (indentSpaces >= level - 1) {
                // 跳过要去除的子级内容
                continue;
            }
            sb.append(line).append(System.lineSeparator());
        }
        return sb.toString().trim();
    }

    /**
     * 去除多行文本中的时间，如果匹配到多个时间，则移除最后一个
     *
     * @param raw 原始文本
     * @return 去除时间后的文本
     */
    public static String removeTime(String raw) {
        raw = StrUtil.trim(raw);
        if (StrUtil.isEmpty(raw)) {
            return StrUtil.EMPTY;
        }
        String[] lines = raw.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            Pair<String, Double> pair = TimeExtractUtil.extractLineTime(line, MyTimeUnit.MINUTE);
            sb.append(pair.getKey()).append(System.lineSeparator());
        }
        return sb.toString().trim();
    }

    /**
     * 计算前导空格或\t数（层级）
     *
     * @param line 行文本
     * @return 前导空格数
     */
    private static int countLeadingSpaces(String line) {
        int count = 0;
        while (count < line.length()) {
            char c = line.charAt(count);
            if (!(c == ' ' || c == '\t')) {
                break;
            }
            count++;
        }
        return count;
    }

    /**
     * 更新计数器栈
     *
     * @param countStack 计数器栈
     * @param level      当前层级
     */
    private static void updateCounter(List<Integer> countStack, int level) {
        while (countStack.size() <= level) {
            countStack.add(0);
        }
        countStack.set(level, countStack.get(level) + 1);
        for (int i = level + 1; i < countStack.size(); i++) {
            countStack.set(i, 0);
        }
    }

    /**
     * 格式化时间
     * 如 60min 格式化为 -1.00h
     *
     * @param time 分钟数
     * @return 小时数
     */
    private static String formatTime(int time) {
        if (time != 0) {
            return String.format("-%.2fh", time / 60.0);
        }
        return StrUtil.EMPTY;
    }

    @NoArgsConstructor
    @EqualsAndHashCode
    private static final class ContentNode {
        private int level;
        private int index;
        private String content;
        private int time;
        private List<ContentNode> children;

        private ContentNode(int level, String content, int time) {
            this.level = level;
            this.content = content;
            this.time = time;
        }

        private static ContentNode virtualRoot() {
            ContentNode root = new ContentNode();
            root.level = 0;
            root.children = new ArrayList<>();
            return root;
        }

        /**
         * 合并相同行内容的子列表，不递归，只合并第一级
         *
         * @param root 根节点
         */
        public static void mergeAndResort(ContentNode root) {
            Map<String, ContentNode> children = new HashMap<>();
            Set<ContentNode> reIndexNodes = new HashSet<>();
            reIndexNodes.add(root);
            root.children.forEach(node -> {
                if (!children.containsKey(node.content)) {
                    children.put(node.content, node);
                    if (node.children == null) {
                        node.children = new ArrayList<>();
                    }
                } else {
                    if (CollUtil.isNotEmpty(node.children)) {
                        children.get(node.content).children.addAll(node.children);
                        reIndexNodes.add(children.get(node.content));
                    }
                    if (node.time != 0) {
                        children.get(node.content).time += node.time;
                    }
                }

            });
            root.children.clear();
            root.children.addAll(children.values());
            // 对变更过的子节点重新索引
            for (ContentNode node : reIndexNodes) {
                List<ContentNode> tempChildren = node.children;
                // 按拼音排序
                List<ContentNode> sortedChildren = tempChildren.stream().sorted((s1, s2) -> {
                    String pinyin1 = PinyinUtil.getPinyin(s1.content, "");
                    String pinyin2 = PinyinUtil.getPinyin(s2.content, "");
                    int result = pinyin1.compareToIgnoreCase(pinyin2);
                    // 如果拼音相同，按原字符串排序
                    return result != 0 ? result : s1.content.compareTo(s2.content);
                }).collect(Collectors.toList());
                // 重新索引
                for (int i = 0; i < sortedChildren.size(); i++) {
                    sortedChildren.get(i).index = i + 1;
                }
                node.children = sortedChildren;
            }
        }

        public void addChild(ContentNode node) {
            if (this.children == null) {
                this.children = new ArrayList<>();
            }
            this.children.add(node);
        }

        private static List<ContentNode> flatten(ContentNode root) {
            List<ContentNode> result = new ArrayList<>();
            if (root.children == null) {
                return result;
            }
            for (ContentNode node : root.children) {
                result.add(node);
                result.addAll(flatten(node));
            }
            return result;
        }
    }
}