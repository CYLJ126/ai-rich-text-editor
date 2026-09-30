package com.arte.ai.strategy.model;

import com.arte.ai.common.enums.ReasoningEffortEnum;
import com.arte.ai.api.ModelConfigService;
import com.arte.ai.pojo.chat.ChatRequestDto;
import com.arte.ai.pojo.model.ModelConfigDto;
import com.arte.core.enums.TextFormatEnum;
import com.arte.core.utils.crypto.Sm2Util;
import com.arte.core.utils.crypto.Sm2UtilForSmCrypto;
import com.sun.net.httpserver.HttpServer;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class DeepSeekModelAdapterTest {

    private final DeepSeekModelAdapter adapter = new DeepSeekV4FlashModelAdapter();

    @Test
    public void shouldKeepDeepSeekVisionOptionsAndDisableThinking() {
        OpenAiChatOptions options = adapter.buildVisionOptions(new ChatRequestDto()
                .setModelId("deepseek-v4-flash")
                .setReasoningEffort(ReasoningEffortEnum.NONE)
                .setMaxTokens(512).setTemperature(0.3).setTopP(0.95)
                .setTextType(TextFormatEnum.JSON)
                .setExtraParam(Map.of("response_format", "text", "reasoning_effort_list", List.of("high", "max"))));

        Assert.assertEquals("deepseek-v4-flash", options.getModel());
        Assert.assertEquals(Integer.valueOf(512), options.getMaxTokens());
        Assert.assertNull(options.getMaxCompletionTokens());
        Assert.assertEquals(Double.valueOf(0.3), options.getTemperature());
        Assert.assertEquals(Double.valueOf(0.95), options.getTopP());
        Assert.assertEquals("none", options.getReasoningEffort());
        Assert.assertEquals(Map.of("type", "disabled"), options.getExtraBody().get("thinking"));
        Assert.assertEquals(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT, options.getResponseFormat().getType());
        Assert.assertFalse(options.getExtraBody().containsKey("response_format"));
    }

    @Test
    public void shouldPreserveVisionReasoningEffortWithoutForcingIt() {
        Assert.assertEquals("max", adapter.buildVisionOptions(new ChatRequestDto()
                .setModelId("deepseek-v4-pro").setReasoningEffort(ReasoningEffortEnum.MAX)).getReasoningEffort());
        OpenAiChatOptions options = adapter.buildVisionOptions(new ChatRequestDto().setModelId("deepseek-v4-flash"));
        Assert.assertNull(options.getReasoningEffort());
        Assert.assertFalse(options.getExtraBody().containsKey("thinking"));
    }

    @Test
    public void shouldSendCurrentAndHistoryImagesInStreamingRequest() throws Exception {
        ObjectMapper objectMapper = DeepSeekThinkingRequestBodySupport.buildObjectMapper();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                    + "\"model\":\"deepseek-v4-flash\",\"choices\":[{\"index\":0,"
                    + "\"delta\":{\"role\":\"assistant\",\"content\":\"diagram\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            Sm2Util.SM2KeyPair keyPair = Sm2Util.generateKeyPair();
            adapter.privateKey = keyPair.privateKey();
            ModelConfigDto config = new ModelConfigDto();
            config.setApiKey(Sm2UtilForSmCrypto.encryptForSmCrypto("test-key", keyPair.publicKey()));
            config.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            config.setMaxRetries(0);
            config.setTimeoutSeconds(10);
            adapter.modelConfigService = (ModelConfigService) Proxy.newProxyInstance(
                    ModelConfigService.class.getClassLoader(), new Class<?>[]{ModelConfigService.class},
                    (proxy, method, args) -> config);
            byte[] image = Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
            Media media = new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(image));
            ChatClient client = adapter.buildChatClient(new ChatRequestDto()
                    .setModelAutoId(7).setModelId("deepseek-v4-flash").setEnableVision(true)
                    .setReasoningEffort(ReasoningEffortEnum.NONE).setMaxTokens(512));
            List<String> response = client.prompt().messages(List.of(
                    UserMessage.builder().text("Reference image").media(List.of(media)).build(),
                    new AssistantMessage("What should change?"),
                    UserMessage.builder().text("Draw this image").media(List.of(media)).build()))
                    .stream().content().collectList().block(Duration.ofSeconds(10));
            Assert.assertEquals(List.of("diagram"), response);

            JsonNode body = objectMapper.readTree(capturedBody.get());
            Assert.assertTrue(body.path("stream").asBoolean());
            Assert.assertEquals("disabled", body.path("thinking").path("type").asText());
            Assert.assertEquals(512, body.path("max_tokens").asInt());
            for (int index : List.of(0, 2)) {
                JsonNode content = body.path("messages").get(index).path("content");
                Assert.assertEquals("text", content.get(0).path("type").asText());
                Assert.assertEquals("image_url", content.get(1).path("type").asText());
                Assert.assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(image),
                        content.get(1).path("image_url").path("url").asText());
            }
        } finally {
            server.stop(0);
        }
    }

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
