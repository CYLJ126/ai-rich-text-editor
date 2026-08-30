package com.arte.app.pojo.richtext;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/**
 * Tiptap 文本 Mark（bold / italic / code / highlight / strike / link 等）
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 19:59 ✾
 **/
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TiptapMark {
    @JsonProperty("type")
    private String type;
    @JsonProperty("attrs")
    private Map<String, Object> attrs;

    public String getAttrString(String key) {
        if (attrs == null) return null;
        Object val = attrs.get(key);
        return val != null ? val.toString() : null;
    }
}
