package com.nip.app.common.utils;

import com.nip.app.pojo.richtext.TiptapMark;
import com.nip.app.pojo.richtext.TiptapNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;

/**
 * 文本提取工具：从 Tiptap 节点中提取纯文本 / 带 Mark 信息的文本
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 20:11 ✾
 **/
@Slf4j
public final class TextExtractUtil {
    private TextExtractUtil() {
    }

    /**
     * 提取节点的纯文本（递归）
     */
    public static String extractPlainText(TiptapNode node) {
        if (node == null) return "";
        if ("text".equals(node.getType())) {
            return node.getText() != null ? node.getText() : "";
        }
        if ("hardBreak".equals(node.getType())) {
            return "\n";
        }
        if (!node.hasContent()) return "";
        StringBuilder sb = new StringBuilder();
        for (TiptapNode child : node.getContent()) {
            sb.append(extractPlainText(child));
        }
        return sb.toString();
    }

    /**
     * 从节点（及其子节点）中提取纯文本，同时收集 marks 信息。
     * <p>
     * 支持的子节点类型：
     * - text       : 直接取 text 字段，解析 marks（bold / highlight / strike）
     * - hardBreak  : 输出换行符
     * - inlineMath : 取 attrs.latex，以 $latex$ 形式嵌入文本
     * - image/audio/video：生成媒体引用，加入 mediaRefs
     * - 其他含 content 的容器节点：递归处理
     * </p>
     */
    public static String extractTextWithMarks(TiptapNode node,
                                              Set<String> boldTerms,
                                              Set<String> highlightTerms,
                                              List<String> mediaRefs,
                                              boolean[] hasStrikethrough) {
        if (node == null) return "";
        List<TiptapNode> children = node.getContent();
        if (children == null || children.isEmpty()) {
            // 叶子节点（如直接传入 text 节点）
            return extractLeafText(node, boldTerms, highlightTerms, mediaRefs, hasStrikethrough);
        }
        StringBuilder sb = new StringBuilder();
        for (TiptapNode child : children) {
            if (child == null || child.getType() == null) continue;
            sb.append(extractChildText(child, boldTerms, highlightTerms, mediaRefs, hasStrikethrough));
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------
    //  子节点分发
    // ----------------------------------------------------------------
    private static String extractChildText(TiptapNode child,
                                           Set<String> boldTerms,
                                           Set<String> highlightTerms,
                                           List<String> mediaRefs,
                                           boolean[] hasStrikethrough) {
        return switch (child.getType()) {
            case "text" -> extractLeafText(child, boldTerms, highlightTerms,
                    mediaRefs, hasStrikethrough);
            case "hardBreak" -> "\n";
            case "inlineMath" -> extractInlineMath(child);
            case "image", "audio", "video" -> extractMedia(child, mediaRefs);
            default -> {
                // 未知容器节点：递归子节点
                if (child.hasContent()) {
                    yield extractTextWithMarks(child, boldTerms, highlightTerms,
                            mediaRefs, hasStrikethrough);
                }
                yield "";
            }
        };
    }

    // ----------------------------------------------------------------
    //  text 叶子节点：提取文本 + 解析 marks
    // ----------------------------------------------------------------
    private static String extractLeafText(TiptapNode node,
                                          Set<String> boldTerms,
                                          Set<String> highlightTerms,
                                          List<String> mediaRefs,
                                          boolean[] hasStrikethrough) {
        String text = node.getText();
        if (text == null || text.isEmpty()) return "";
        List<TiptapMark> marks = node.getMarks();
        if (marks == null || marks.isEmpty()) return text;
        boolean isBold = false;
        boolean isHighlight = false;
        boolean isStrike = false;
        for (TiptapMark mark : marks) {
            if (mark == null || mark.getType() == null) continue;
            switch (mark.getType()) {
                case "bold" -> isBold = true;
                case "highlight" -> isHighlight = true;
                case "strike" -> isStrike = true;
                // link / code / italic 等：不额外处理，文本照常输出
                default -> {
                }
            }
        }
        if (isBold) boldTerms.add(text);
        if (isHighlight) highlightTerms.add(text);
        if (isStrike) hasStrikethrough[0] = true;
        return text;
    }

    // ----------------------------------------------------------------
    //  inlineMath 节点：取 attrs.latex 转为 $latex$
    // ----------------------------------------------------------------
    private static String extractInlineMath(TiptapNode node) {
        String latex = node.getAttrString("latex");
        if (latex == null || latex.isBlank()) {
            log.debug("inlineMath node id={} has empty latex", node.getAttrString("id"));
            return "";
        }
        // 以标准 LaTeX 行内公式格式嵌入文本，便于检索和展示
        return "$" + latex + "$";
    }

    // ----------------------------------------------------------------
    //  媒体节点：生成媒体引用描述
    // ----------------------------------------------------------------
    private static String extractMedia(TiptapNode node, List<String> mediaRefs) {
        String ref = MediaRefUtil.buildRef(node);
        mediaRefs.add(ref);
        return ref;
    }
}
