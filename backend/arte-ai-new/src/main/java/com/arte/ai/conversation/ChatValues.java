package com.arte.ai.conversation;

import com.arte.ai.context.ResourceContextValues;
import com.arte.ai.model.context.ContextBudget;
import com.arte.ai.model.context.ContextHistoryRef;
import com.arte.ai.model.context.ContextSnapshot;
import com.arte.ai.model.context.ResourceContextSnapshot;
import com.arte.ai.model.conversation.Turn;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.model.generation.ModelOptions;
import com.arte.ai.model.message.Message;
import com.arte.ai.model.message.TextPart;
import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.execution.ResultCertainty;
import com.arte.base.model.execution.SideEffectStatus;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;

/**
 * 规范摘要使用长度编码；不依赖 JSON 字段顺序或供应商协议格式。
 */
public final class ChatValues {
    private ChatValues() {
    }

    public static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public static BaseException failure(CommonErrorCode code, String stage) {
        return new BaseException(ExecutionError.of(code, stage, false, SideEffectStatus.NONE, ResultCertainty.CONFIRMED, null));
    }

    public static String modelKey(Turn turn) {
        return "chat:" + turn.turnId();
    }

    public static String submission(String conversation, long version, String original, List<Message> input, ModelOptions options) {
        return hash(out -> {
            field(out, "arte.chat.submission.v1");
            field(out, conversation);
            out.writeLong(version);
            field(out, original);
            messages(out, input);
            out.writeBoolean(options.temperature() != null);
            if (options.temperature() != null) out.writeDouble(options.temperature());
            out.writeBoolean(options.maxOutputTokens() != null);
            if (options.maxOutputTokens() != null) out.writeInt(options.maxOutputTokens());
        });
    }

    public static String context(String conversation, long version, DefinitionRef binding, List<Message> messages,
                                 List<ContextHistoryRef> history, int byteLimit, int usedBytes, int outputTokens) {
        return context(conversation, version, binding, messages, history, new ContextBudget(byteLimit, usedBytes, outputTokens));
    }

    public static String context(String conversation, long version, DefinitionRef binding, List<Message> messages,
                                 List<ContextHistoryRef> history, ContextBudget budget) {
        return hash(out -> {
            field(out, budget.contextWindowTokens() == null ? "arte.chat.context.v1" : "arte.chat.context.v2");
            field(out, conversation);
            out.writeLong(version);
            field(out, binding.definitionType());
            field(out, binding.definitionId());
            field(out, binding.version());
            messages(out, messages);
            out.writeInt(history.size());
            for (var ref : history) {
                field(out, ref.turnId());
                out.writeLong(ref.turnVersion());
                field(out, ref.executionId());
            }
            out.writeInt(budget.inputByteLimit());
            out.writeInt(budget.usedInputBytes());
            out.writeInt(budget.outputTokenReserve());
            if (budget.contextWindowTokens() != null) {
                out.writeInt(budget.contextWindowTokens());
                out.writeInt(budget.inputTokenLimit());
                out.writeInt(budget.estimatedInputTokens());
                out.writeInt(budget.safetyTokenReserve());
                field(out, budget.estimatorVersion());
            }
        });
    }

    public static String submission(String conversation, long version, String original, List<Message> input, ModelOptions options,
                                    ResourceContextSnapshot resources) {
        String plain = submission(conversation, version, original, input, options);
        return resources == null ? plain : hash(out -> { field(out, "arte.chat.submission.resources.v1"); field(out, plain); field(out, resources.contentDigest()); });
    }

    public static String context(String conversation, long version, DefinitionRef binding, List<Message> messages,
                                 List<ContextHistoryRef> history, ContextBudget budget, ResourceContextSnapshot resources) {
        String plain = context(conversation, version, binding, messages, history, budget);
        return resources == null ? plain : hash(out -> { field(out, "arte.chat.context.resources.v1"); field(out, plain); field(out, resources.contentDigest()); });
    }

    public static void verify(ContextSnapshot snapshot) {
        if (snapshot.resourceContext() != null) ResourceContextValues.verify(snapshot.resourceContext());
        if ((snapshot.resourceContext() == null && !snapshot.fragments().isEmpty()) || !snapshot.contentDigest().equals(context(snapshot.conversationId(), snapshot.conversationVersion(),
                snapshot.modelBindingRef(), snapshot.messages(), snapshot.history(), snapshot.budget(), snapshot.resourceContext())))
            throw failure(CommonErrorCode.VERSION_CONFLICT, "chat-context");
        if (bytes(snapshot.messages()) != snapshot.budget().usedInputBytes())
            throw failure(CommonErrorCode.VERSION_CONFLICT, "chat-context");
    }

    public static int bytes(List<Message> messages) {
        long size = 0;
        for (var message : messages)
            for (var part : message.parts()) {
                if (!(part instanceof TextPart text)) throw failure(CommonErrorCode.UNSUPPORTED, "chat-content");
                size += text.text().getBytes(StandardCharsets.UTF_8).length;
            }
        if (size > Integer.MAX_VALUE) throw failure(CommonErrorCode.INVALID_ARGUMENT, "chat-capacity");
        return (int) size;
    }

    private static void messages(DataOutputStream out, List<Message> messages) throws IOException {
        out.writeInt(messages.size());
        for (var message : messages) {
            field(out, message.role().name());
            out.writeInt(message.parts().size());
            for (var part : message.parts()) {
                if (!(part instanceof TextPart text)) throw failure(CommonErrorCode.UNSUPPORTED, "chat-content");
                field(out, text.text());
            }
        }
    }

    private static void field(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String hash(Encoder encoder) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                encoder.write(out);
            }
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @FunctionalInterface
    private interface Encoder {
        void write(DataOutputStream out) throws IOException;
    }
}
