package com.arte.ai.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.arte.core.enums.MyEnum;
import lombok.Getter;

/**
 * 模型类型
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 17:42 ✾
 **/
@Getter
public enum ModelTypeEnum implements IEnum<String>, MyEnum<String> {
    CHAT("chat", "聊天模型"),
    EMBEDDING("embedding", "嵌入模型"),
    RERANK("rerank", "排序模型"),
    MULTIMODAL("multimodal", "多模态模型");

    private final String value;
    private final String description;

    ModelTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
