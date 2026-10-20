package com.arte.ainew.application.tool.chat;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.tool.ToolSetSnapshot;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.core.enums.ResultCodeEnum;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * 可信编排消息的有界容量估算，计入工具描述、Schema、调用参数、结果和逐消息协议余量。
 * 仅用于容量／报价上界，实际计费继续取供应商可信用量；不截断工具配对。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 22:00 ✾
 */
public final class ToolChatInputs {

    public static final String TOKENIZER = "tool-chat-utf8-estimate-v1";

    private ToolChatInputs() {
    }

    public static String digest(List<ChatMessage> messages, ToolSetSnapshot tools) {
        return CanonicalJson.digest(CanonicalJson.fields("messages", messages, "tools", tools.descriptions(),
                "schemas", tools.entries().stream().map(e -> e.inputSchema().document()).toList()));
    }

    public static long estimate(List<ChatMessage> messages, ToolSetSnapshot tools, NewAiProperties.Limits limits) {
        if (messages.isEmpty() || messages.size() > com.arte.ainew.common.validation.ContractChecks.MAX_ITEMS
                || messages.stream().mapToLong(ChatMessage::characterCount).sum() > com.arte.ainew.common.validation.ContractChecks.MAX_TEXT_CHARS)
            throw new AdmissionException(ResultCodeEnum.AI_INPUT_LIMIT_EXCEEDED);
        if (messages.stream().map(ChatMessage::messageId).distinct().count() != messages.size())
            throw new AdmissionException(ResultCodeEnum.AI_INVALID_CONTEXT_SNAPSHOT);
        if (messages.stream().flatMap(m -> m.content().stream()).anyMatch(p -> !(p instanceof ChatMessage.Text)))
            throw new AdmissionException(ResultCodeEnum.AI_CONTEXT_SELECTION_NOT_SUPPORTED);
        String encoded = CanonicalJson.encode(CanonicalJson.fields("messages", messages, "tools", tools.descriptions(),
                "schemas", tools.entries().stream().map(e -> e.inputSchema().document()).toList()));
        long bytes = encoded.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > limits.maxInputBytes()) throw new AdmissionException(ResultCodeEnum.AI_INPUT_LIMIT_EXCEEDED);
        return Math.addExact(bytes, 256L + 64L * messages.size() + 64L * tools.entries().size());
    }

    public static ContextSnapshot snapshot(String id, ContextSnapshot original, List<ChatMessage> messages,
                                           ToolSetSnapshot tools, Instant at, NewAiProperties.Limits limits) {
        long tokens = estimate(messages, tools, limits);
        if (tokens > original.budget().maxInputTokens())
            throw new AdmissionException(ResultCodeEnum.AI_CONTEXT_CAPACITY_EXCEEDED);
        return new ContextSnapshot(id, original.history(), original.modelBinding(), messages, List.of(), original.budget(),
                tokens, true, TOKENIZER, List.of(), digest(messages, tools), at, original.expiresAt());
    }

    public static void verify(ContextSnapshot input, ToolSetSnapshot tools, NewAiProperties.Limits limits) {
        if (!TOKENIZER.equals(input.tokenizerVersion()) || !input.estimatedTokens()
                || input.inputTokens() != estimate(input.messages(), tools, limits)
                || !input.contentDigest().equals(digest(input.messages(), tools))
                || !input.fragments().isEmpty() || !input.truncations().isEmpty())
            throw new AdmissionException(ResultCodeEnum.AI_INVALID_CONTEXT_SNAPSHOT);
    }
}
