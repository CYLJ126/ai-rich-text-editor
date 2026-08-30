package com.arte.app.pojo.richtext;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Tiptap JSON 节点模型
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 19:58 ✾
 **/
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TiptapNode {
    /**
     * 节点类型: doc / heading / paragraph / codeBlock / table 等
     */
    @JsonProperty("type")
    private String type;

    /**
     * 节点属性
     */
    @JsonProperty("attrs")
    private Map<String, Object> attrs;

    /**
     * 子节点
     */
    @JsonProperty("content")
    private List<TiptapNode> content;

    /**
     * 文本内容（type=text时有效）
     */
    @JsonProperty("text")
    private String text;

    /**
     * 文本标记
     */
    @JsonProperty("marks")
    private List<TiptapMark> marks;

    // -------- 便捷方法 --------
    public String getAttrString(String key) {
        if (attrs == null) return null;
        Object val = attrs.get(key);
        return val != null ? val.toString() : null;
    }

    public Integer getAttrInt(String key) {
        if (attrs == null) return null;
        Object val = attrs.get(key);
        if (val == null) return null;
        if (val instanceof Integer i) return i;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString());
        } catch (Exception e) {
            return null;
        }
    }

    public boolean hasContent() {
        return content != null && !content.isEmpty();
    }

    public boolean hasMarks() {
        return marks != null && !marks.isEmpty();
    }

    public boolean hasMark(String markType) {
        if (marks == null) return false;
        return marks.stream().anyMatch(m -> markType.equals(m.getType()));
    }
}

