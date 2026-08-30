package com.nip.ai.advisor;

import com.nip.ai.common.enums.MessageRoleEnum;
import com.nip.ai.common.enums.MessageStatusEnum;
import com.nip.ai.pojo.chat.ChatRequestDto;
import com.nip.ai.pojo.message.MessageDto;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class PersistentChatMemoryAdvisorTest {

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
