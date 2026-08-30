package com.nip.ai.service;

import com.nip.ai.api.*;
import com.nip.ai.common.enums.MessageRoleEnum;
import com.nip.ai.pojo.FrontendSaveRequestDto;
import com.nip.ai.pojo.conversation.ConversationDto;
import com.nip.ai.pojo.message.MessageAttachmentDto;
import com.nip.ai.pojo.message.MessageDto;
import com.nip.ai.pojo.message.SaveMessageDto;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 前端模式实现类
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/29 13:39 ✾
 **/
@Service
@Slf4j
public class FrontEndChatServiceImpl implements FrontEndChatService {

    @Resource
    private ConversationService conversationService;

    @Resource
    private MessageService messageService;

    @Resource
    private TokenUsageService tokenUsageService;

    @Resource
    private MessageAttachmentService attachmentService;

    @Override
    public void saveMessage(FrontendSaveRequestDto saveRequest) {
        // 校验会话权限
        conversationService.getAndValidate(saveRequest.getConvId());
        // 1. 保存用户消息
        SaveMessageDto userMsgDto = saveRequest.getUserMessage();
        if (userMsgDto != null) {
            MessageDto userMessage = MessageDto.buildSaveMessage(saveRequest.getConvId(), userMsgDto);
            userMessage.setRole(MessageRoleEnum.USER);
            messageService.saveOrUpdate(userMessage);
            // 绑定附件
            bindAttachments(userMsgDto.getAttachmentIds(), userMessage.getMessageId(),
                    saveRequest.getConvId());
        }
        // 2. 保存 AI 消息
        SaveMessageDto aiMsgDto = saveRequest.getAssistantMessage();
        if (aiMsgDto != null) {
            MessageDto aiMessage = MessageDto.buildSaveMessage(saveRequest.getConvId(), aiMsgDto);
            aiMessage.setRole(MessageRoleEnum.ASSISTANT);
            messageService.saveOrUpdate(aiMessage);
            // 记录 token 用量
            saveTokenUsage(saveRequest.getConvId(), aiMsgDto);
        }
        conversationService.lambdaUpdate()
                .eq(ConversationDto::getConvId, saveRequest.getConvId())
                .set(ConversationDto::getLastMessageAt, LocalDateTime.now())
                .set(ConversationDto::getUpdateTime, LocalDateTime.now())
                .update();
        log.debug("[FrontendInteraction] 前端交互数据保存完成, convId={}", saveRequest.getConvId());
    }

    private void bindAttachments(List<String> attachmentIds, String messageId, String convId) {
        if (CollectionUtils.isEmpty(attachmentIds)) return;
        attachmentIds.forEach(attId -> {
            attachmentService.lambdaUpdate()
                    .eq(MessageAttachmentDto::getId, attId)
                    .set(MessageAttachmentDto::getMessageId, messageId)
                    .set(MessageAttachmentDto::getConvId, convId)
                    .update();
        });
    }

    private void saveTokenUsage(String convId, SaveMessageDto aiMsgDto) {
        // TODO
    }
}
