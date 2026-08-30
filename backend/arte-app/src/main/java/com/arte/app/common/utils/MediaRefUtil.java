package com.arte.app.common.utils;

import com.arte.app.pojo.richtext.TiptapNode;

import java.util.StringJoiner;

/**
 * 将 Tiptap 媒体节点的 src、title、alt 转换为可检索的文本引用。
 */
public final class MediaRefUtil {
    private MediaRefUtil() {
    }

    public static String buildRef(TiptapNode node) {
        if (node == null || node.getType() == null) return "[媒体]";

        String typeName = switch (node.getType()) {
            case "image" -> "图片";
            case "audio" -> "音频";
            case "video" -> "视频";
            default -> "媒体";
        };
        StringJoiner attributes = new StringJoiner("; ");
        addAttribute(attributes, "src", node.getAttrString("src"));
        addAttribute(attributes, "title", node.getAttrString("title"));
        addAttribute(attributes, "alt", node.getAttrString("alt"));
        return attributes.length() == 0
                ? "[" + typeName + "]"
                : "[" + typeName + ": " + attributes + "]";
    }

    private static void addAttribute(StringJoiner attributes, String name, String value) {
        if (value != null && !value.isBlank()) {
            attributes.add(name + "=" + value.trim());
        }
    }
}
