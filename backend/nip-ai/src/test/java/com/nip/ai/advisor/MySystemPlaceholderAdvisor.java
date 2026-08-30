package com.nip.ai.advisor;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Map;

/**
 * 自定义 Advisor，如果系统文本中包含 {voice}（说明没被手工替换，这里做默认值设置），则替换为默认值
 *
 * @author zhangsc
 * @since 2025/6/5 13:40
 */
@NoArgsConstructor
@Slf4j
public class MySystemPlaceholderAdvisor implements BaseAdvisor {
    // 默认值映射
    private static final Map<String, String> DEFAULT_VALUES = Map.of("{voice}", "唐国强");

    private int order;

    public MySystemPlaceholderAdvisor(int order) {
        this.order = order;
    }

    @Override
    public String getName() {
        return "mySystemPlaceholderAdvisor";
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        Prompt prompt = chatClientRequest.prompt();
        SystemMessage systemMessage = prompt.getSystemMessage();
        log.info("系统文本：\n{}\n---------------", systemMessage.getText());
        if (systemMessage.getText().contains("{voice}")) {
            Prompt newPrompt = prompt.augmentSystemMessage(systemMessage.getText().replaceAll("\\{voice\\}", DEFAULT_VALUES.get("{voice}")));
            return chatClientRequest.mutate().prompt(newPrompt).build();
        }
        return chatClientRequest;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        return this.order;
    }
}
