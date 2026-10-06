package com.arte.ainew.application.support;

import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.core.enums.ResultCodeEnum;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 首批单条用户文本规则；估算明确标记为估算，不假装供应商 tokenizer 或计费用量。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
public final class TextInputs {

    public static final String TOKENIZER = "utf8-estimate-v1";

    private TextInputs() {
    }

    public static void validate(List<ChatMessage> messages, NewAiProperties.Limits limits) {
        if (messages.size() != 1 || messages.getFirst().role() != ChatMessage.Role.USER
                || !messages.getFirst().toolCalls().isEmpty() || messages.getFirst().toolCallId() != null
                || messages.getFirst().content().stream().anyMatch(part -> !(part instanceof ChatMessage.Text))) {
            throw new AdmissionException(ResultCodeEnum.AI_ONLY_SINGLE_USER_TEXT_SUPPORTED);
        }
        if (utf8Bytes(messages) > limits.maxInputBytes()) {
            throw new AdmissionException(ResultCodeEnum.AI_INPUT_LIMIT_EXCEEDED);
        }
    }

    private static long utf8Bytes(List<ChatMessage> messages) {
        return messages.stream().flatMap(message -> message.content().stream())
                .mapToLong(part -> ((ChatMessage.Text) part).text().getBytes(StandardCharsets.UTF_8).length).sum();
    }

    public static long estimatedTokens(List<ChatMessage> messages) {
        return Math.addExact(64, utf8Bytes(messages));
    }

    /**
     * 消息 ID 为服务端分配的身份，首批无工具时不进入实际文本语义摘要。
     */
    public static Object semanticMessages(List<ChatMessage> messages) {
        return messages.stream().map(message -> CanonicalJson.fields("role", message.role(),
                "textParts", message.content().stream().map(part -> ((ChatMessage.Text) part).text()).toList())).toList();
    }

    public static String contentDigest(List<ChatMessage> messages) {
        return CanonicalJson.digest(semanticMessages(messages));
    }

    public static void verify(ContextSnapshot snapshot, ResolvedBinding binding, NewAiProperties.Limits limits) {
        validate(snapshot.messages(), limits);
        if (!snapshot.modelBinding().equals(binding.definition()) || snapshot.history() != null
                || !snapshot.fragments().isEmpty() || !snapshot.truncations().isEmpty()
                || snapshot.budget().contextWindowTokens() != binding.contextWindowTokens()
                || snapshot.budget().reservedToolTokens() != 0
                || !snapshot.contentDigest().equals(contentDigest(snapshot.messages()))
                || snapshot.inputTokens() != estimatedTokens(snapshot.messages()) || !snapshot.estimatedTokens()
                || !TOKENIZER.equals(snapshot.tokenizerVersion())) {
            throw new AdmissionException(ResultCodeEnum.AI_INVALID_CONTEXT_SNAPSHOT);
        }
        if (snapshot.inputTokens() > snapshot.budget().maxInputTokens()) {
            throw new AdmissionException(ResultCodeEnum.AI_CONTEXT_CAPACITY_EXCEEDED);
        }
    }
}
