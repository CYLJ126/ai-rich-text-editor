package com.arte.ai.strategy.model;

import com.arte.ai.api.ModelAdapter;
import com.arte.ai.common.enums.ModelProviderEnum;
import com.arte.ai.pojo.chat.ChatRequestDto;
import com.arte.ai.pojo.model.ModelConfigDto;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;
import java.util.Map;

public class ModelAdapterFactoryTest {

    @Test
    public void shouldUseProviderFallbackForDynamicModels() {
        ModelAdapter openAi = new TestModelAdapter(ModelProviderEnum.OPENAI, "registered-openai-model");
        ModelAdapter deepSeek = new TestModelAdapter(ModelProviderEnum.DEEPSEEK, "registered-deepseek-model");
        ModelAdapterFactory factory = new ModelAdapterFactory(List.of(openAi, deepSeek));
        factory.init();

        Assert.assertSame(openAi, factory.getAdapter(ModelProviderEnum.OPENAI, "new-openai-model"));
        Assert.assertSame(deepSeek, factory.getAdapter(ModelProviderEnum.DEEPSEEK, "new-deepseek-model"));
        Assert.assertSame(openAi, factory.getAdapter(ModelProviderEnum.QIAN_WEN, "qwen-new"));
        Assert.assertSame(openAi, factory.getAdapter(ModelProviderEnum.CLAUDE, "claude-new"));
        Assert.assertSame(openAi, factory.getAdapter(ModelProviderEnum.MISTRAL, "mistral-new"));
        Assert.assertSame(openAi, factory.getAdapter(ModelProviderEnum.OPEN_ROUTER, "vendor/model"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectProviderWithoutCompatibleAdapter() {
        ModelAdapterFactory factory = new ModelAdapterFactory(List.of(
                new TestModelAdapter(ModelProviderEnum.DEEPSEEK, "registered-deepseek-model")));
        factory.init();

        factory.getAdapter(ModelProviderEnum.CLAUDE, "claude-model");
    }

    private record TestModelAdapter(ModelProviderEnum provider, String modelId) implements ModelAdapter {
        @Override
        public ChatModel getChatModel(ModelConfigDto modelConfigDto, ChatOptions chatOptions) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatClient buildChatClient(ChatRequestDto chatRequest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatOptions buildOptions(ChatRequestDto chatRequest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean supportsCapability(String capability) {
            return false;
        }

        @Override
        public ModelConfigDto getOfficialModelConfig() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Message> handleResponse(ChatResponse chatResponse, Map<String, Object> context) {
            throw new UnsupportedOperationException();
        }
    }
}
