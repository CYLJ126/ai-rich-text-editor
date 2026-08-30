package com.arte.ai.pojo.chat;

import com.arte.ai.common.enums.KnowledgeBaseTypeEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Set;

/**
 * Rag 相关参数，knowledgeBaseType 有值，且对应类型的参数存在，才会生效
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class ChatRagRequestDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 1556124228596514592L;
    /**
     * 知识库类型
     */
    private KnowledgeBaseTypeEnum knowledgeBaseType;
    /**
     * 文章 id 列表
     */
    private Set<Integer> articleIds;
    // TODO 可在此添加知识库类型的检索参数

}
