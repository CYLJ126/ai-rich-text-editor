package com.arte.app.common.enums.richtext;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.arte.core.enums.MyEnum;
import lombok.Getter;

import java.util.List;
import java.util.Set;

/**
 * Tiptap 分块类型枚举
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 17:30 ✾
 **/
@Getter
public enum TiptapNodeTypeEnum implements IEnum<String>, MyEnum<String> {
    PARAGRAPH("paragraph", "段落"),
    HEADING("heading", "标题"),
    CODE_BLOCK("code_block", "代码块"),
    TABLE("table", "表格"),
    MERMAID("mermaid", "mermaid 图"),
    BLOCK_MATH("block_math", "块级数学公式"),
    INLINE_MATH("inline_math", "行内数学公式"),
    ;

    /**
     * 已知节点类型
     */
    public static final List<String> KNOWN_NODE_TYPES = List.of(
            "heading", "paragraph", "codeBlock", "table", "horizontalRule",
            "image", "audio", "video", "mermaid", "blockMath", "inlineMath",
            "orderedList", "bulletList", "taskList",
            "youtube", "blockquote", "listItem", "taskItem",
            "hardBreak", "text"
    );

    /**
     * 兜底：无匹配策略时用于处理有子节点的容器型节点
     */
    public static final Set<String> CONTAINER_NODE_TYPES = Set.of(
            "doc", "blockquote", "listItem", "taskItem"
    );

    private final String value;
    private final String description;

    TiptapNodeTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
