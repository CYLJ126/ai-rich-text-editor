package com.arte.ai.advisor;

import com.arte.ai.common.enums.MessageRoleEnum;
import com.arte.ai.common.enums.MessageStatusEnum;
import com.arte.ai.pojo.chat.ChatRequestDto;
import com.arte.ai.pojo.message.MessageDto;
import com.arte.ai.pojo.message.MessageAttachmentDto;
import com.arte.ai.api.MessageAttachmentService;
import com.arte.ai.api.MessageService;
import com.arte.ai.common.enums.ContextStrategyEnum;
import com.arte.core.enums.TextFormatEnum;
import com.arte.core.pojo.UserContext;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

public class PersistentChatMemoryAdvisorTest {

    @Test
    public void shouldLoadHistoryImagesWithoutThreadLocalUserContext() throws Exception {
        UserContext.clear();
        PersistentChatMemoryAdvisor advisor = new PersistentChatMemoryAdvisor();
        List<MessageDto> stored = new ArrayList<>();
        for (int id = 1; id <= 2; id++) {
            MessageDto message = new MessageDto();
            message.setId(id);
            message.setMessageId("user-" + id);
            message.setConvId("conv-1");
            message.setRole(MessageRoleEnum.USER);
            message.setContent("Reference " + id);
            message.setTextType(TextFormatEnum.MARKDOWN);
            message.setStatus(MessageStatusEnum.COMPLETED);
            stored.add(message);
        }
        advisor.messageService = (MessageService) Proxy.newProxyInstance(
                MessageService.class.getClassLoader(), new Class<?>[]{MessageService.class},
                (proxy, method, args) -> {
                    Assert.assertEquals("selectConversationContextMessages", method.getName());
                    return stored;
                });
        MessageAttachmentDto attachment = new MessageAttachmentDto();
        attachment.setMessageId("user-1");
        Media image = new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[]{1, 2}));
        List<Object[]> queries = new ArrayList<>();
        MessageAttachmentService attachments = (MessageAttachmentService) Proxy.newProxyInstance(
                MessageAttachmentService.class.getClassLoader(), new Class<?>[]{MessageAttachmentService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("listImages")) {
                        Assert.assertFalse(UserContext.hasUserOnlineInfo());
                        queries.add(args);
                        return List.of(attachment);
                    }
                    Assert.assertEquals("readImages", method.getName());
                    return ((List<?>) args[0]).isEmpty() ? List.of() : List.of(image);
                });
        var field = PersistentChatMemoryAdvisor.class.getDeclaredField("messageAttachmentService");
        field.setAccessible(true);
        field.set(advisor, attachments);
        var method = PersistentChatMemoryAdvisor.class.getDeclaredMethod("getHistoryMessages", ChatRequestDto.class);
        method.setAccessible(true);
        ChatRequestDto request = new ChatRequestDto().setConvId("conv-1").setUserName("tester")
                .setContextStrategy(ContextStrategyEnum.WINDOW).setContextWindow(12).setEnableVision(true);

        @SuppressWarnings("unchecked")
        List<Message> history = (List<Message>) method.invoke(advisor, request);
        Assert.assertEquals(List.of(image), ((UserMessage) history.get(0)).getMedia());
        Assert.assertTrue(((UserMessage) history.get(1)).getMedia().isEmpty());
        Assert.assertEquals(1, queries.size());
        Assert.assertArrayEquals(new Object[]{"conv-1", List.of("user-1", "user-2"), "tester"}, queries.get(0));

        queries.clear();
        @SuppressWarnings("unchecked")
        List<Message> textHistory = (List<Message>) method.invoke(advisor, request.setEnableVision(false));
        Assert.assertTrue(queries.isEmpty());
        Assert.assertTrue(((UserMessage) textHistory.get(0)).getMedia().isEmpty());
    }

    @Test
    public void shouldStreamOriginalChunksAndAggregateOnlyForPersistence() {
        PersistentChatMemoryAdvisor advisor = new PersistentChatMemoryAdvisor();
        AtomicReference<ChatClientResponse> aggregated = new AtomicReference<>();

        ChatClientResponse first = response("Hello ");
        ChatClientResponse second = response("world");
        List<ChatClientResponse> streamed = advisor
                .streamAndAggregate(Flux.just(first, second), aggregated::set)
                .collectList()
                .block();

        Assert.assertNotNull(streamed);
        Assert.assertEquals(2, streamed.size());
        Assert.assertSame(first, streamed.get(0));
        Assert.assertSame(second, streamed.get(1));
        Assert.assertNotNull(aggregated.get());
        Assert.assertEquals("Hello world",
                aggregated.get().chatResponse().getResult().getOutput().getText());
    }

    @Test
    public void shouldReadGenericReasoningMetadata() {
        PersistentChatMemoryAdvisor advisor = new PersistentChatMemoryAdvisor();
        ChatClientResponse response = responseWithReasoning("answer", "reasoning summary");
        StringBuilder reasoning = new StringBuilder();

        advisor.extractAndAccumulateReasoningContent(response, reasoning);

        Assert.assertEquals("reasoning summary", reasoning.toString());
        Assert.assertEquals("reasoning summary", response.chatResponse().getResult().getOutput()
                .getMetadata().get(AbstractAdvisor.REASONING_CONTENT));
    }

    @Test
    public void shouldRoundTripAssistantToolCalls() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call-1", "function", "random-name", "{\"firstName\":\"明\"}");

        List<Map<String, Object>> stored = PersistentChatMemoryAdvisor.toToolCallMaps(List.of(toolCall));
        List<AssistantMessage.ToolCall> restored = PersistentChatMemoryAdvisor.toAssistantToolCalls(stored);

        Assert.assertEquals(List.of(toolCall), restored);
    }

    @Test
    public void shouldBuildAndRoundTripToolResponseMessage() {
        PersistentChatMemoryAdvisor advisor = new PersistentChatMemoryAdvisor();
        ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse(
                "call-1", "random-name", "王明");
        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(List.of(response))
                .build();
        ChatRequestDto request = new ChatRequestDto()
                .setConvId("conv-1")
                .setModelAutoId(1)
                .setUserName("tester");

        MessageDto stored = advisor.transferToolResponseMessage(toolResponseMessage, request);

        Assert.assertEquals(MessageRoleEnum.TOOL, stored.getRole());
        Assert.assertEquals(MessageStatusEnum.COMPLETED, stored.getStatus());
        Assert.assertEquals("王明", stored.getContent());
        Assert.assertNotNull(stored.getMessageId());
        Assert.assertEquals(List.of(response), PersistentChatMemoryAdvisor.toToolResponses(stored.getToolCalls()));
    }

    private static ChatClientResponse response(String text) {
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))))
                .build();
    }

    private static ChatClientResponse responseWithReasoning(String text, String reasoning) {
        AssistantMessage message = AssistantMessage.builder()
                .content(text)
                .properties(java.util.Map.of("reasoning_summary", reasoning))
                .build();
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(message))))
                .build();
    }
}
