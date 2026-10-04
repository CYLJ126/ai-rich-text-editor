package com.arte.ainew.pojo.generation;

import com.arte.ainew.common.reference.ArtifactRef;
import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * 不依赖模型 SDK 的消息事实；工具调用与响应显式关联。ID 由服务端分配，指令角色不能由客户端任意提升。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record ChatMessage(String messageId, Role role, List<ContentPart> content,
                          List<ToolCall> toolCalls, String toolCallId) implements Serializable {
    public enum Role {SYSTEM, USER, ASSISTANT, TOOL}

    public enum Modality {IMAGE, AUDIO, VIDEO, FILE}

    public sealed interface ContentPart extends Serializable permits Text, Artifact {
        long characterCount();
    }

    public record Text(String text) implements ContentPart {
        public Text {
            ContractChecks.text(text, "text", ContractChecks.MAX_TEXT_CHARS);
        }

        @Override
        public long characterCount() {
            return text.length();
        }
    }

    /**
     * 只携带本系统产物引用；发送前由产物端口授权读取，协议地址由连接适配层生成。
     */
    public record Artifact(Modality modality, ArtifactRef reference) implements ContentPart {
        public Artifact {
            Objects.requireNonNull(modality, "modality");
            Objects.requireNonNull(reference, "reference");
            String prefix = switch (modality) {
                case IMAGE -> "image/";
                case AUDIO -> "audio/";
                case VIDEO -> "video/";
                case FILE -> "";
            };
            ContractChecks.require(reference.mediaType().startsWith(prefix), "Artifact MIME type does not match modality");
        }

        @Override
        public long characterCount() {
            return 0;
        }
    }

    public ChatMessage {
        ContractChecks.id(messageId, "messageId");
        Objects.requireNonNull(role, "role");
        content = ContractChecks.list(content, "content", 0, ContractChecks.MAX_PARTS);
        toolCalls = ContractChecks.list(toolCalls, "toolCalls", 0, ContractChecks.MAX_TOOLS);
        ContractChecks.unique(toolCalls.stream().map(ToolCall::callId).toList(), "tool call IDs");
        ContractChecks.optionalId(toolCallId, "toolCallId");
        ContractChecks.require(!content.isEmpty() || role == Role.ASSISTANT && !toolCalls.isEmpty(), "Message content is required");
        ContractChecks.require(role == Role.ASSISTANT || toolCalls.isEmpty(), "Only assistant messages can propose tools");
        ContractChecks.require((role == Role.TOOL) == (toolCallId != null), "Tool response must have matching call identity");
        ContractChecks.require(content.stream().mapToLong(ContentPart::characterCount).sum()
                        + toolCalls.stream().mapToLong(call -> call.arguments().characterCount()).sum() <= ContractChecks.MAX_TEXT_CHARS,
                "Message exceeds character limit");
    }

    public long characterCount() {
        return content.stream().mapToLong(ContentPart::characterCount).sum()
                + toolCalls.stream().mapToLong(call -> call.arguments().characterCount()).sum();
    }
}
