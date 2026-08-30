package com.nip.ai.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.nip.core.enums.MyEnum;
import lombok.Getter;

/**
 * 知识库类型枚举
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/9 10:09 ✾
 **/
@Getter
public enum KnowledgeBaseTypeEnum implements IEnum<String>, MyEnum<String> {
    ARTICLE("article", "articleRetrievalAugmentationAdvisor", "文章检索"),
    PLAN("plan", "planRetrievalAugmentationAdvisor", "计划管理"),
    INFORMATION("information", "informationRetrievalAugmentationAdvisor", "信息管理"),
    GENERAL("general", "generalRetrievalAugmentationAdvisor", "通用（默认）类型"); // 按钮“添加到知识库”时，默认进到通用知识库

    private final String value;
    private final String beanName;
    private final String description;

    KnowledgeBaseTypeEnum(String value, String beanName, String description) {
        this.value = value;
        this.beanName = beanName;
        this.description = description;
    }

    public String print() {
        return String.format("%s-%s", this.value, this.description);
    }
}
