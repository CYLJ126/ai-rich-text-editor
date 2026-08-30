package com.arte.ai.strategy.model;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

public class DeepSeekModelAdapterTest {

    @Test
    public void shouldAddThinkingDisabledToRequestBody() throws Exception {
        DeepSeekApi.ChatCompletionRequest request = new DeepSeekApi.ChatCompletionRequest(
                List.of(new DeepSeekApi.ChatCompletionMessage(
                        "测试",
                        DeepSeekApi.ChatCompletionMessage.Role.USER)),
                "deepseek-test",
                null,
                512,
                null,
                null,
                null,
                true,
                null,
                0.8,
                null,
                null,
                null,
                null);

        ObjectMapper objectMapper = DeepSeekThinkingRequestBodySupport.buildObjectMapper();
        JsonNode requestBody = objectMapper.readTree(objectMapper.writeValueAsString(request));

        Assert.assertEquals("disabled", requestBody.path("thinking").path("type").asText());
        Assert.assertEquals("测试", requestBody.path("messages").get(0).path("content").asText());
        Assert.assertTrue(requestBody.path("stream").asBoolean());
        Assert.assertEquals(512, requestBody.path("max_tokens").asInt());
        Assert.assertEquals(0.8, requestBody.path("top_p").asDouble(), 0.0);
        Assert.assertFalse(requestBody.has("maxTokens"));
        Assert.assertFalse(requestBody.has("topP"));
    }
}
