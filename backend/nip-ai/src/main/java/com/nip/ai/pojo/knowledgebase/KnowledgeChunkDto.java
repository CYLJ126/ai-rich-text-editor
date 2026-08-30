package com.nip.ai.pojo.knowledgebase;

import com.nip.ai.common.enums.KnowledgeBaseTypeEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库内容条目（分块） DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/9 10:08 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class KnowledgeChunkDto<T> implements Serializable {
    @Serial
    private static final long serialVersionUID = -9014238294254944527L;

    /**
     * 知识库类型
     */
    private KnowledgeBaseTypeEnum knowledgeBaseType;
    /**
     * 条目/分块 ID
     */
    private String chunkId;
    /**
     * 条目/分块内容
     */
    private String content;
    /**
     * 嵌入向量
     */
    private float[] embedding;
    /**
     * 分块相似度，即相关性得分
     */
    private Double score;
    /**
     * 元数据
     */
    private T metadata;
}
