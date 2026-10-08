package com.arte.ai.utils;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.arte.ai.api.AssistantService;
import com.arte.ai.api.ConversationService;
import com.arte.ai.api.MessageService;
import com.arte.ai.api.ModelConfigService;
import com.arte.ai.common.enums.MessageRoleEnum;
import com.arte.ai.pojo.RegenerateRequestDto;
import com.arte.ai.pojo.assistant.AssistantDto;
import com.arte.ai.pojo.chat.ChatRequestDto;
import com.arte.ai.pojo.chat.ChatRequestParam;
import com.arte.ai.pojo.conversation.ConversationDto;
import com.arte.ai.pojo.message.MessageDto;
import com.arte.ai.pojo.model.ModelConfigDto;
import com.arte.core.exception.ChatException;
import com.arte.core.i18n.MessageUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 请求参数处理
 * 优先级从低到高依次为：
 * 1. 填充模型默认参数；
 * 2. 如果有助手，则覆盖相应字段；
 * 3. 如果会话有配置，则覆盖相应字段；
 * 4. 用当前请求的参数覆盖相应字段；
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/21 10:36 ✾
 **/
@Slf4j
@Service
public class RequestParamHandler {

    @Resource
    private ModelConfigService modelConfigService;

    @Resource
    private ConversationService conversationService;

    @Resource
    private AssistantService assistantService;

    @Resource
    private MessageService messageService;

    public ChatRequestDto handleChatRequest(ChatRequestParam chatRequestParam) {
        if (chatRequestParam == null) {
            throw new ChatException("error.ai.paramRequired");
        }
        validateMessageIds(chatRequestParam);
        String conversationId = chatRequestParam.getConvId();
        if (StrUtil.isBlank(conversationId)) {
            throw new ChatException("error.ai.convIdRequired");
        }
        ConversationDto conversation = conversationService.getAndValidate(conversationId);
        AssistantDto assistant = null;
        if (Objects.nonNull(conversation.getAssistantId())) {
            assistant = assistantService.getById(conversation.getAssistantId());
            if (Objects.isNull(assistant)) {
                assistant = assistantService.getDefaultAssistant(chatRequestParam.getUserName());
            }
        }
        ModelConfigDto modelConfig = getModelConfig(chatRequestParam, conversation, assistant);
        ModelConfigDto defaultModelConfig = modelConfigService.getDefaultModelConfig(chatRequestParam.getUserName());
        if (defaultModelConfig == null) defaultModelConfig = modelConfig;
        if (defaultModelConfig == null) {
            throw new ChatException(MessageUtils.get("error.ai.userDefaultModelNotSet", chatRequestParam.getUserName()));
        }
        if (modelConfig == null) {
            modelConfig = defaultModelConfig;
        }
        ChatRequestDto chatRequestDto = new ChatRequestDto()
                .fillModelConfig(modelConfig)
                .fillAssistantConfig(assistant)
                .fillConversationConfig(conversation)
                .fillChatRequestParam(chatRequestParam);
        chatRequestDto.setDefaultModelConfig(defaultModelConfig);
        adjustModelParam(modelConfig, chatRequestDto);
        if (StrUtil.isNotBlank(chatRequestDto.getQuotedMessageId())) {
            MessageDto quotedMessage = messageService.getByMessageId(chatRequestDto.getQuotedMessageId());
            if (Objects.isNull(quotedMessage)) {
                throw new ChatException(MessageUtils.get("error.ai.quotedMessageNotFound", chatRequestDto.getQuotedMessageId()));
            }
            chatRequestDto.setQuotedMessage(quotedMessage);
        }
        return chatRequestDto;
    }

    public ChatRequestDto handleGenerateRequest(ChatRequestParam chatRequestParam) {
        if (chatRequestParam == null) {
            throw new ChatException("error.ai.paramRequired");
        }
        validateMessageIds(chatRequestParam);
        ModelConfigDto modelConfig = resolveModel(chatRequestParam.getModelId(), chatRequestParam.getUserName());
        if (modelConfig == null) {
            modelConfig = modelConfigService.getDefaultModelConfig(chatRequestParam.getUserName());
            if (modelConfig == null) {
                // 当前用户没有可用的默认模型时，抛出异常
                throw new ChatException("error.ai.defaultModelNotSet");
            }
        }
        AssistantDto assistant = assistantService.getDefaultAssistant(chatRequestParam.getUserName());
        ChatRequestDto chatRequestDto = new ChatRequestDto()
                .fillModelConfig(modelConfig)
                .fillAssistantConfig(assistant)
                .fillChatRequestParam(chatRequestParam);
        adjustModelParam(modelConfig, chatRequestDto);
        return chatRequestDto;
    }

    public ChatRequestDto handleRegenerateRequest(RegenerateRequestDto request) {
        if (request == null || StrUtil.isBlank(request.getMessageId())) {
            throw new ChatException("error.ai.messageIdRequired");
        }
        MessageDto assistantMessage = messageService.getByMessageId(request.getMessageId());
        if (assistantMessage == null || Boolean.TRUE.equals(assistantMessage.getDeleteFlag())) {
            throw new ChatException("error.ai.regenerateMessageNotFound");
        }
        if (assistantMessage.getRole() != MessageRoleEnum.ASSISTANT) {
            throw new ChatException("error.ai.onlyRegenerateAssistant");
        }
        MessageDto userMessage = messageService.lambdaQuery()
                .eq(MessageDto::getConvId, assistantMessage.getConvId())
                .eq(MessageDto::getRole, MessageRoleEnum.USER)
                .eq(MessageDto::getDeleteFlag, false)
                .lt(MessageDto::getId, assistantMessage.getId())
                .orderByDesc(MessageDto::getId)
                .last("limit 1")
                .one();
        if (userMessage == null) {
            throw new ChatException("error.ai.parentReplyNotFound");
        }

        ChatRequestParam chatRequest = new ChatRequestParam()
                .setConvId(assistantMessage.getConvId())
                .setContent(userMessage.getContent())
                .setModelId(assistantMessage.getModelId())
                .setQuotedMessageId(userMessage.getQuotedMessageId())
                .setUserName(request.getUserName())
                .setUserMessageId(userMessage.getMessageId())
                .setAssistantMessageId(assistantMessage.getMessageId());
        ChatRequestDto chatRequestDto = handleChatRequest(chatRequest);
        chatRequestDto.setRegenerate(true);
        return chatRequestDto;
    }

    private static void validateMessageIds(ChatRequestParam chatRequestParam) {
        if (StrUtil.isBlank(chatRequestParam.getUserMessageId())) {
            throw new ChatException("error.ai.userMessageIdRequired");
        }
        if (StrUtil.isBlank(chatRequestParam.getAssistantMessageId())) {
            throw new ChatException("error.ai.assistantMessageIdRequired");
        }
    }

    private static void adjustModelParam(ModelConfigDto modelConfig, ChatRequestDto chatRequestDto) {
        if (!Boolean.TRUE.equals(modelConfig.getSupportThinking())) {
            chatRequestDto.setReasoningEffort(null);
        }
        if (!Boolean.TRUE.equals(modelConfig.getSupportSearch())) {
            chatRequestDto.setEnableSearch(null);
        }
        if (!Boolean.TRUE.equals(modelConfig.getSupportVision())) {
            chatRequestDto.setEnableVision(null);
        }
        if (ObjectUtil.isAllNotEmpty(chatRequestDto.getMaxTokens(), modelConfig.getMaxTokens())) {
            // 不能超过模型的最大输出 token
            if (chatRequestDto.getMaxTokens() > modelConfig.getMaxTokens()) {
                chatRequestDto.setMaxTokens(modelConfig.getMaxTokens());
            }
        }
    }

    private ModelConfigDto resolveModel(Integer id, String userName) {
        if (id == null) return null;
        ModelConfigDto model = modelConfigService.getAccessibleModel(id, userName);
        if (model == null) throw new ChatException("error.ai.modelConfigNotAccessible");
        return model;
    }

    private ModelConfigDto getModelConfig(ChatRequestParam chatRequestParam, ConversationDto conversation, AssistantDto assistant) {
        if (Objects.nonNull(chatRequestParam.getModelId())) {
            return resolveModel(chatRequestParam.getModelId(), chatRequestParam.getUserName());
        }
        if (Objects.nonNull(conversation.getModelId())) {
            return resolveModel(conversation.getModelId(), chatRequestParam.getUserName());
        }
        if (assistant != null && assistant.getModelId() != null) {
            return resolveModel(assistant.getModelId(), chatRequestParam.getUserName());
        }

        // TODO 判断是否超限，如 RPM、TPM 等
        // TODO 判断是否支持对应能力，如 supportVision、supportSearch 等
        return null;
    }

}
