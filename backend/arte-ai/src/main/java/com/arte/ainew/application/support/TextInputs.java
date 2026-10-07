package com.arte.ainew.application.support;

import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.core.enums.ResultCodeEnum;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 单条用户输入及可信历史上下文规则；估算明确标记为估算，不假装供应商 tokenizer 或计费用量。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
@Slf4j
public final class TextInputs {

    public static final String TOKENIZER = "utf8-estimate-v1";

    /**
     * 为角色标记、消息边界等额外信息粗略预留的固定 Token 开销。
     * 整组消息仅加一次，不按消息条数累加；64 是人为设定的估算余量，没有精确的模型依据。
     * 仅用于上下文容量估算，不代表供应商实际 Token 用量或计费用量。
     */
    private static final long ESTIMATED_MESSAGE_OVERHEAD = 64L;

    private TextInputs() {
    }

    public static void validate(List<ChatMessage> messages, NewAiProperties.Limits limits) {
        if (messages.size() != 1) {
            throw new AdmissionException(ResultCodeEnum.AI_ONLY_SINGLE_USER_TEXT_SUPPORTED);
        }
        validateContext(messages, limits);
    }

    /**
     * 仅供受信组装器及已保存快照复核；HTTP 调用方仍只能提交一个 USER 文本。
     */
    public static void validateContext(List<ChatMessage> messages, NewAiProperties.Limits limits) {
        if (messages.isEmpty() || messages.size() > 21 || messages.size() % 2 != 1) {
            throw new AdmissionException(ResultCodeEnum.AI_INVALID_CONTEXT_SNAPSHOT);
        }
        for (int i = 0; i < messages.size(); i++) {
            var message = messages.get(i);
            // 非 USER/ASSISTANT 角色消息、工具调用、工具调用 ID 不为空、非纯文本内容的消息，拒绝接收（暂只支持文本输入）
            if (message.role() != (i % 2 == 0 ? ChatMessage.Role.USER : ChatMessage.Role.ASSISTANT)
                    || !message.toolCalls().isEmpty() || message.toolCallId() != null
                    || message.content().stream().anyMatch(part -> !(part instanceof ChatMessage.Text))) {
                throw new AdmissionException(ResultCodeEnum.AI_ONLY_SINGLE_USER_TEXT_SUPPORTED);
            }
        }
        // 输入字节长度超出限制
        if (utf8Bytes(messages) > limits.maxInputBytes()) {
            throw new AdmissionException(ResultCodeEnum.AI_INPUT_LIMIT_EXCEEDED);
        }
    }

    /**
     * 计算输入消息的 UTF-8 字节数。
     *
     * @param messages 消息列表
     * @return 字节数
     */
    private static long utf8Bytes(List<ChatMessage> messages) {
        return messages.stream().flatMap(message -> message.content().stream())
                .mapToLong(part -> ((ChatMessage.Text) part).text().getBytes(StandardCharsets.UTF_8).length).sum();
    }

    /**
     * 按文本 UTF-8 字节数加一次固定开销估算输入 Token，用于上下文容量检查。
     * 此公式未使用模型 tokenizer，不能准确反映多轮消息的协议开销。
     *
     * @param messages 消息列表
     * @return 估算的 token 数量
     */
    public static long estimatedTokens(List<ChatMessage> messages) {
        return Math.addExact(ESTIMATED_MESSAGE_OVERHEAD, utf8Bytes(messages));
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
        validateContext(snapshot.messages(), limits);
        if (!snapshot.modelBinding().equals(binding.definition())
                || (snapshot.history() == null ? snapshot.messages().size() != 1
                : snapshot.messages().size() != snapshot.history().turnIds().size() * 2 + 1)
                || !snapshot.fragments().isEmpty() || !snapshot.truncations().isEmpty()
                || snapshot.budget().contextWindowTokens() != binding.contextWindowTokens()
                || snapshot.budget().reservedToolTokens() != 0
                || !snapshot.contentDigest().equals(contentDigest(snapshot.messages()))
                || snapshot.inputTokens() != estimatedTokens(snapshot.messages()) || !snapshot.estimatedTokens()
                || !TOKENIZER.equals(snapshot.tokenizerVersion())) {
            throw new AdmissionException(ResultCodeEnum.AI_INVALID_CONTEXT_SNAPSHOT);
        }
        if (snapshot.inputTokens() > snapshot.budget().maxInputTokens()) {
            log.info("SnapshotId: {} - Context capacity exceeded: {} > {}", snapshot.snapshotId(), snapshot.inputTokens(), snapshot.budget().maxInputTokens());
            throw new AdmissionException(ResultCodeEnum.AI_CONTEXT_CAPACITY_EXCEEDED);
        }
    }
}
