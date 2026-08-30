package com.arte.ai.tool;

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
import com.arte.core.constant.CoreConstant;
import com.arte.core.exception.ChatException;
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
            throw new ChatException("请求参数不能为空");
        }
        validateMessageIds(chatRequestParam);
        String conversationId = chatRequestParam.getConvId();
        if (StrUtil.isBlank(conversationId)) {
            throw new ChatException("会话 ID 不能为空");
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
        if (defaultModelConfig == null) {
            // 当前用户未配置默认模型时，使用后台 system 用户的默认模型
            defaultModelConfig = modelConfigService.getDefaultModelConfig(CoreConstant.SYSTEM_USER_NAME);
        }
        if (defaultModelConfig == null) {
            throw new ChatException(String.format("用户【%s】未指定默认模型", chatRequestParam.getUserName()));
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
                throw new ChatException("引用消息不存在，ID：" + chatRequestDto.getQuotedMessageId());
            }
            chatRequestDto.setQuotedMessage(quotedMessage);
        }
        return chatRequestDto;
    }

    public ChatRequestDto handleGenerateRequest(ChatRequestParam chatRequestParam) {
        if (chatRequestParam == null) {
            throw new ChatException("请求参数不能为空");
        }
        validateMessageIds(chatRequestParam);
        ModelConfigDto modelConfig = modelConfigService.getById(chatRequestParam.getModelId());
        if (modelConfig == null) {
            modelConfig = modelConfigService.getDefaultModelConfig(chatRequestParam.getUserName());
            if (modelConfig == null) {
                // 当前用户未配置默认模型时，使用后台 system 用户的默认模型
                modelConfig = modelConfigService.getDefaultModelConfig(CoreConstant.SYSTEM_USER_NAME);
            }
            if (modelConfig == null) {
                // 后台 system 用户未配置默认模型时，抛出异常
                throw new ChatException("未指定默认模型");
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
            throw new ChatException("消息 ID 不能为空");
        }
        MessageDto assistantMessage = messageService.getByMessageId(request.getMessageId());
        if (assistantMessage == null || Boolean.TRUE.equals(assistantMessage.getDeleteFlag())) {
            throw new ChatException("要重新生成的消息不存在");
        }
        if (assistantMessage.getRole() != MessageRoleEnum.ASSISTANT) {
            throw new ChatException("只能重新生成 AI 消息");
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
            throw new ChatException("未找到该回复对应的用户消息");
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
            throw new ChatException("用户消息 ID 不能为空");
        }
        if (StrUtil.isBlank(chatRequestParam.getAssistantMessageId())) {
            throw new ChatException("AI 响应消息 ID 不能为空");
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

    private ModelConfigDto getModelConfig(ChatRequestParam chatRequestParam, ConversationDto conversation, AssistantDto assistant) {
        if (Objects.nonNull(chatRequestParam.getModelId())) {
            return modelConfigService.getById(chatRequestParam.getModelId());
        }
        if (Objects.nonNull(conversation.getModelId())) {
            return modelConfigService.getById(conversation.getModelId());
        }
        if (assistant != null && assistant.getModelId() != null) {
            return modelConfigService.getById(assistant.getModelId());
        }

        // TODO 判断是否有权调用此模型（如是否挂在对应组织下）
        // TODO 判断是否超限，如 RPM、TPM 等
        // TODO 判断是否支持对应能力，如 supportVision、supportSearch 等
        return null;
    }

}
