package com.arte.ainew.persistence.codec;

import com.arte.ainew.common.execution.ExecutionEvent;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.BudgetReservation;
import com.arte.ainew.pojo.budget.BudgetSettlement;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.embedding.EmbeddingRequest;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationEvent;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.media.MediaRequest;
import com.arte.ainew.pojo.remote.RemoteApplicationRequest;
import com.arte.ainew.pojo.tool.ToolInvocation;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.NamedType;

import java.util.Map;

/**
 * 隔离的内部快照 v1 编码器。稳定别名白名单，不复用应用全局 ObjectMapper。
 * StructuredValue 在此使用显式类型包装的内部格式；HTTP 标准 JSON 编码另由传输适配器负责。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:11 ✾
 */
public final class JacksonExecutionRecordCodec implements ExecutionRecordCodec {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    private interface Named {
    }

    private interface MoneyShape {
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        java.math.BigDecimal amount();
    }

    private static final Map<Class<?>, String> ROOTS = Map.ofEntries(
            Map.entry(Invocation.class, "invocation"), Map.entry(Attempt.class, "attempt"),
            Map.entry(ExecutionEvent.class, "execution-event"), Map.entry(Conversation.class, "conversation"),
            Map.entry(Turn.class, "turn"), Map.entry(BudgetCommands.Account.class, "budget-account"),
            Map.entry(BudgetReservation.class, "budget-reservation"), Map.entry(BudgetSettlement.class, "budget-settlement"),
            Map.entry(ContextSnapshot.class, "context-snapshot"), Map.entry(InvocationResult.class, "invocation-result"));
    private final JsonMapper mapper;

    public JacksonExecutionRecordCodec() {
        var builder = JsonMapper.builder();
        builder.addMixIn(Money.class, MoneyShape.class);
        for (var base : new Class<?>[]{CapabilityInput.class, ChatMessage.ContentPart.class,
                GenerationRequest.OutputFormat.class, GenerationEvent.class, ExecutionEvent.Payload.class, StructuredValue.class,
                InvocationResult.class}) {
            builder.addMixIn(base, Named.class);
        }
        builder.registerSubtypes(
                new NamedType(InvocationResult.Generation.class, "result-generation"),
                new NamedType(InvocationResult.Embedding.class, "result-embedding"),
                new NamedType(InvocationResult.Tool.class, "result-tool"),
                new NamedType(InvocationResult.Media.class, "result-media"),
                new NamedType(InvocationResult.RemoteApplication.class, "result-remote"),
                new NamedType(GenerationRequest.class, "generation"), new NamedType(EmbeddingRequest.class, "embedding"),
                new NamedType(ToolInvocation.class, "tool"), new NamedType(MediaRequest.class, "media"),
                new NamedType(RemoteApplicationRequest.class, "remote"),
                new NamedType(ChatMessage.Text.class, "text"), new NamedType(ChatMessage.Artifact.class, "artifact"),
                new NamedType(GenerationRequest.TextOutput.class, "text-output"),
                new NamedType(GenerationRequest.StructuredOutput.class, "structured-output"),
                new NamedType(GenerationEvent.TextDelta.class, "text-delta"),
                new NamedType(GenerationEvent.ToolCallDelta.class, "tool-call-delta"),
                new NamedType(GenerationEvent.UsageReported.class, "usage"), new NamedType(GenerationEvent.Finished.class, "finished"),
                new NamedType(ExecutionPayload.OutputBatch.class, "output-batch"),
                new NamedType(ExecutionPayload.Status.class, "status"), new NamedType(ExecutionPayload.Control.class, "control"),
                new NamedType(ExecutionPayload.Terminal.class, "terminal"),
                new NamedType(StructuredValue.ObjectValue.class, "object"), new NamedType(StructuredValue.ArrayValue.class, "array"),
                new NamedType(StructuredValue.StringValue.class, "string"), new NamedType(StructuredValue.NumberValue.class, "number"),
                new NamedType(StructuredValue.BooleanValue.class, "boolean"), new NamedType(StructuredValue.NullValue.class, "null"));
        mapper = builder.build();
    }

    @Override
    public String encode(Object value) {
        validate(value);
        var alias = ROOTS.get(value instanceof InvocationResult ? InvocationResult.class : value.getClass());
        if (alias == null) {
            throw new IllegalArgumentException("Unregistered snapshot type");
        }
        var node = mapper.createObjectNode();
        node.put("schemaVersion", 1).put("type", alias);
        node.set("body", mapper.valueToTree(value));
        var json = mapper.writeValueAsString(node);
        if (json.length() > 4_000_000) {
            throw new IllegalArgumentException("Snapshot exceeds limit");
        }
        return json;
    }

    @Override
    public <T> T decode(String json, Class<T> expectedType) {
        var alias = ROOTS.get(expectedType);
        if (alias == null || json.length() > 4_000_000) {
            throw new IllegalArgumentException("Unregistered or oversized snapshot");
        }
        var node = mapper.readTree(json);
        if (node.path("schemaVersion").asInt() != 1 || !alias.equals(node.path("type").asString())) {
            throw new IllegalArgumentException("Unsupported snapshot schema");
        }
        var value = mapper.treeToValue(node.required("body"), expectedType);
        validate(value);
        return value;
    }

    private void validate(Object value) {
        if (value instanceof Invocation invocation) {
            var input = invocation.request().input().getClass();
            if (input != GenerationRequest.class && input != EmbeddingRequest.class && input != ToolInvocation.class
                    && input != MediaRequest.class && input != RemoteApplicationRequest.class) {
                throw new IllegalArgumentException("Unregistered capability input");
            }
        }
        if (value instanceof ExecutionEvent<?> event) {
            String alias = switch (event.payload()) {
                case ExecutionPayload.OutputBatch ignored -> "output-batch";
                case ExecutionPayload.Status ignored -> "status";
                case ExecutionPayload.Control ignored -> "control";
                case ExecutionPayload.Terminal ignored -> "terminal";
                default -> throw new IllegalArgumentException("Unregistered event payload");
            };
            if (event.schemaVersion() != 1 || event.payloadVersion() != 1 || !alias.equals(event.payloadType())) {
                throw new IllegalArgumentException("Unsupported event schema");
            }
        }
    }
}
