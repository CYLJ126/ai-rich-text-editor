package com.nip.ai.common.exception;

import com.nip.ai.common.enums.KnowledgeBaseTypeEnum;
import com.nip.core.enums.ResultCodeEnum;
import com.nip.core.exception.CommonException;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serial;

/**
 * 检索增强异常类
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/10 16:58 ✾
 **/
@Getter
@NoArgsConstructor
public class RetrievalAugmentationException extends CommonException {

    @Serial
    private static final long serialVersionUID = -253339274182424045L;

    private KnowledgeBaseTypeEnum knowledgeBaseType;

    public RetrievalAugmentationException(KnowledgeBaseTypeEnum knowledgeBaseType) {
        super(ResultCodeEnum.RETRIEVAL_EXCEPTION, ResultCodeEnum.RETRIEVAL_EXCEPTION.getDesc() + ": " + knowledgeBaseType.print());
        this.knowledgeBaseType = knowledgeBaseType;
    }

    public RetrievalAugmentationException(KnowledgeBaseTypeEnum knowledgeBaseType, Throwable ex) {
        super(ResultCodeEnum.RETRIEVAL_EXCEPTION, ResultCodeEnum.RETRIEVAL_EXCEPTION.getDesc() + ": " + knowledgeBaseType.print(), ex);
        this.knowledgeBaseType = knowledgeBaseType;
    }
}
