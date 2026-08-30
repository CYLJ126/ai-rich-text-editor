package com.arte.ai.advisor;

import com.arte.ai.common.enums.KnowledgeBaseTypeEnum;
import com.arte.ai.pojo.chat.ChatRagRequestDto;
import com.arte.ai.pojo.chat.ChatRequestDto;
import org.springframework.ai.chat.client.ChatClientRequest;

import java.util.Objects;

/**
 * 检索增强 Advisor 基础类
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/10 16:50 ✾
 **/
public abstract class AbstractRetrievalAugmentationAdvisor extends AbstractAdvisor {

    protected abstract KnowledgeBaseTypeEnum getKnowledgeBaseType();

    protected abstract String getRetrievalContextParamKey();

    protected boolean canUseRetrieval(ChatClientRequest chatClientRequest) {
        ChatRequestDto chatRequestDto = getChatRequestDto(chatClientRequest);
        ChatRagRequestDto chatRagRequest = chatRequestDto.getChatRagRequest();
        // 本次发送请求中有指定知识库参数
        if (Objects.nonNull(chatRagRequest)) {
            if (chatRagRequest.getKnowledgeBaseType() == getKnowledgeBaseType()) {
                return true;
            }
        }
        // 会话或助手上有指定知识库
        return Objects.equals(chatRequestDto.getKnowledgeBaseId(), getKnowledgeBaseType());
    }
}
