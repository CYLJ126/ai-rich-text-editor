package com.arte.ai.advisor;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Content;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 打印日志
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/18 20:06 ✾
 **/
@NullMarked
@Slf4j
@Service
public class RequestHandlingAdvisor extends AbstractAdvisor {

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public int getOrder() {
        // 请求时最后一个调用，响应时第一个调用
        return 999;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        Prompt prompt = chatClientRequest.prompt();
        ChatOptions chatOptions = prompt.getOptions();
        List<Message> messages = handleSystemMessage(prompt.getInstructions());
        Prompt newPrompt = new Prompt(messages, chatOptions);
        String conversationId = getConversationId(chatClientRequest);
        Map<String, Object> modelParam = new HashMap<>();
        modelParam.put("messages", objectMapper.writeValueAsString(messages));
        modelParam.put("options", chatOptions != null ? chatOptions.toString() : "");
        modelParam.put("conv_id", conversationId);
        log.info("会话 ID：{}，请求指令：【{}】", conversationId, modelParam);
        Map<String, Object> context = putIntoRequest(true, chatClientRequest, MODEL_PARAM, modelParam);
        return ChatClientRequest.builder().prompt(newPrompt).context(context).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    public List<Message> handleSystemMessage(List<Message> instructions) {
        List<Message> systemMessages = new ArrayList<>();
        List<Message> userOrAssistantMessages = new ArrayList<>();
        instructions.forEach(message -> {
            if (message instanceof SystemMessage) {
                systemMessages.add(message);
            } else {
                userOrAssistantMessages.add(message);
            }
        });

        if (systemMessages.size() <= 1) {
            return instructions;
        }
        // 拼接所有系统消息为一条——发送模型时只能有一条系统提示
        String content = systemMessages.stream().map(Content::getText).filter(StrUtil::isNotBlank).collect(Collectors.joining("\n\n"));
        // 系统提示放在最前面
        userOrAssistantMessages.addFirst(new SystemMessage(content));
        return userOrAssistantMessages;
    }
}
