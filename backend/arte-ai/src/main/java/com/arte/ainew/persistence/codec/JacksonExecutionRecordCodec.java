package com.arte.ainew.persistence.codec;

import com.arte.ainew.common.execution.ExecutionEvent;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.budget.*;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.ConversationList;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.embedding.EmbeddingRequest;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationEvent;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.media.MediaRequest;
import com.arte.ainew.pojo.remote.RemoteApplicationRequest;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.ResolvedBindingJson;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.NamedType;

import java.util.Map;

/**
 * 隔离的内部快照编码器：现有类型保持 v1，resolved-binding 写 v2 并兼容旧远端形状。
 * 默认绑定 ENSURE_DEFAULT 回执单独写 v2，其他绑定回执保持 v1；两种形状严格匹配版本。
 * 工具执行快照按无审批只读 v1／人工只读 v2／受控领域写入 v3 严格匹配，旧形状不放宽。
 * 稳定别名白名单，不复用应用全局 ObjectMapper。
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
            Map.entry(ToolChatRunSnapshot.class, "tool-chat-run-input"),
            Map.entry(ToolChatRun.class, "tool-chat-run"),
            Map.entry(ToolChatRunEvent.class, "tool-chat-run-event"),
            Map.entry(ToolChatBudget.class, "tool-chat-budget"),
            Map.entry(ToolChatBudgetAllocation.class, "tool-chat-budget-allocation"),
            Map.entry(ToolChatStep.class, "tool-chat-step"),
            Map.entry(ToolChatCommands.Receipt.class, "tool-chat-run-receipt"),
            Map.entry(ToolApproval.class, "tool-approval"),
            Map.entry(ToolApprovalReceipt.class, "tool-approval-receipt"),
            Map.entry(ToolExecutionSnapshot.class, "tool-execution-snapshot"),
            Map.entry(ToolDiscoveryReceipt.class, "tool-discovery-receipt"),
            Map.entry(ToolBindingDefinition.class, "tool-binding"),
            Map.entry(ToolBindingRevision.class, "tool-binding-revision"),
            Map.entry(ToolBindingResolution.class, "tool-binding-resolution"),
            Map.entry(ToolBindingReceipt.class, "tool-binding-receipt"),
            Map.entry(ToolRelease.class, "tool-release"),
            Map.entry(ToolReleaseReceipt.class, "tool-release-receipt"),
            Map.entry(ToolProviderDefinition.class, "tool-provider"),
            Map.entry(ToolSchemaDefinition.class, "tool-schema"),
            Map.entry(ToolDefinitionRevision.class, "tool-definition-revision"),
            Map.entry(ResolvedBinding.class, "resolved-binding"),
            Map.entry(Reconciliation.Receipt.class, "reconciliation-receipt"),
            Map.entry(RetrySchedule.class, "retry-schedule"), Map.entry(ControlReceipt.class, "control-receipt"), Map.entry(Invocation.class, "invocation"), Map.entry(Attempt.class, "attempt"),
            Map.entry(ExecutionEvent.class, "execution-event"), Map.entry(Conversation.class, "conversation"),
            Map.entry(ConversationList.CommandResult.class, "conversation-metadata-command"),
            Map.entry(ConversationList.Receipt.class, "conversation-metadata-receipt"),
            Map.entry(Turn.class, "turn"), Map.entry(BudgetCommands.Account.class, "budget-account"),
            Map.entry(BudgetReservation.class, "budget-reservation"), Map.entry(BudgetSettlement.class, "budget-settlement"),
            Map.entry(ContextSnapshot.class, "context-snapshot"), Map.entry(InvocationResult.class, "invocation-result"));
    private final JsonMapper mapper;

    public JacksonExecutionRecordCodec() {
        // 快照经 JSON 树中转时也保留精确小数，不能把已校验配置／Schema 先转换为 double。
        var builder = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        builder.addModule(ResolvedBindingJson.module(true));
        builder.addMixIn(Money.class, MoneyShape.class);
        for (var base : new Class<?>[]{CapabilityInput.class, ChatMessage.ContentPart.class,
                GenerationRequest.OutputFormat.class, GenerationEvent.class, ExecutionEvent.Payload.class, StructuredValue.class,
                InvocationResult.class, ExecutionTarget.class, ToolChatStep.Input.class}) {
            builder.addMixIn(base, Named.class);
        }
        builder.registerSubtypes(
                new NamedType(ToolChatStep.Model.class, "chat-step-model"),
                new NamedType(ToolChatStep.Tool.class, "chat-step-tool"),
                new NamedType(ExecutionTarget.Local.class, "target-local"),
                new NamedType(ExecutionTarget.Remote.class, "target-remote"),
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
                new NamedType(ExecutionPayload.BudgetChanged.class, "budget-changed"),
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
        node.put("schemaVersion", value instanceof ToolExecutionSnapshot remote && remote.binding().tool().target() instanceof ExecutionTarget.Remote ? 5 : value instanceof ToolExecutionSnapshot childSnapshot && childSnapshot.request().context().parentExecutionId() != null
                ? 4 : value instanceof ToolExecutionSnapshot writeSnapshot
                && writeSnapshot.binding().tool().tool().risk().sideEffect() == com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect.RESOURCE_WRITE
                ? 3 : value instanceof ToolChatRunSnapshot run && run.budgetPlan() != null || value instanceof Turn || value instanceof ResolvedBinding || value instanceof ToolBindingReceipt receipt
                && receipt.command().action() == ToolBindingCommand.Action.ENSURE_DEFAULT
                || value instanceof ToolExecutionSnapshot snapshot && snapshot.policy().approvalMode() != ToolExecutionPolicy.ApprovalMode.NONE
                ? 2 : 1).put("type", alias);
        node.set("body", mapper.valueToTree(value));
        if (value instanceof ToolChatRunSnapshot run && run.budgetPlan() == null)
            ((tools.jackson.databind.node.ObjectNode) node.get("body")).remove("budgetPlan");
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
        int version = node.path("schemaVersion").asInt();
        if ((expectedType == ResolvedBinding.class || expectedType == ToolRelease.class || expectedType == ToolReleaseReceipt.class || expectedType == ToolDiscoveryReceipt.class
                || expectedType == ToolBindingDefinition.class || expectedType == ToolBindingRevision.class
                || expectedType == ToolBindingResolution.class || expectedType == ToolBindingReceipt.class || expectedType == ToolExecutionSnapshot.class
                || expectedType == ToolApproval.class || expectedType == ToolApprovalReceipt.class
                || expectedType == ToolChatRunSnapshot.class || expectedType == ToolChatRun.class
                || expectedType == ToolChatStep.class || expectedType == ToolChatCommands.Receipt.class || expectedType == ToolChatRunEvent.class || expectedType == Turn.class || expectedType == ToolChatBudget.class || expectedType == ToolChatBudgetAllocation.class)
                && (!node.path("schemaVersion").isIntegralNumber()
                || !node.path("schemaVersion").canConvertToInt()))
            throw new IllegalArgumentException("Snapshot version must be an integer");
        if ((version != 1 && !((expectedType == ToolChatRunSnapshot.class || expectedType == Turn.class || expectedType == ResolvedBinding.class || expectedType == ToolBindingReceipt.class
                || expectedType == ToolExecutionSnapshot.class) && version == 2)
                && !(expectedType == ToolExecutionSnapshot.class && (version == 3 || version == 4 || version == 5)))
                || !alias.equals(node.path("type").asString())) {
            throw new IllegalArgumentException("Unsupported snapshot schema");
        }
        var body = node.required("body");
        if (expectedType == ToolChatRunSnapshot.class && (!body.isObject() || version == 1 && body.has("budgetPlan")
                || version == 2 && !body.hasNonNull("budgetPlan")))
            throw new IllegalArgumentException("Run budget shape does not match snapshot version");
        if (expectedType == Turn.class && (!body.isObject() || version == 1 && (body.has("candidates") || body.has("selectedCandidate"))
                || version == 2 && (!body.hasNonNull("candidates") || !body.has("selectedCandidate"))))
            throw new IllegalArgumentException("Turn shape does not match snapshot version");
        if (expectedType == ResolvedBinding.class
                && (!body.isObject() || version == 1 && body.has("target") || version == 2 && !body.has("target")))
            throw new IllegalArgumentException("Binding snapshot shape does not match version");
        var value = mapper.treeToValue(body, expectedType);
        if (value instanceof ToolExecutionSnapshot snapshot && version != (snapshot.binding().tool().target() instanceof ExecutionTarget.Remote ? 5 : snapshot.request().context().parentExecutionId() != null ? 4 : snapshot.binding().tool().tool().risk().sideEffect()
                == com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect.RESOURCE_WRITE ? 3
                : snapshot.policy().approvalMode() != ToolExecutionPolicy.ApprovalMode.NONE ? 2 : 1))
            throw new IllegalArgumentException("Tool approval policy does not match snapshot version");
        if (value instanceof ToolBindingReceipt receipt && (version == 2)
                != (receipt.command().action() == ToolBindingCommand.Action.ENSURE_DEFAULT))
            throw new IllegalArgumentException("Binding command does not match snapshot version");
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
                case ExecutionPayload.BudgetChanged ignored -> "budget-changed";
                default -> throw new IllegalArgumentException("Unregistered event payload");
            };
            if (event.schemaVersion() != 1 || event.payloadVersion() != 1 || !alias.equals(event.payloadType())) {
                throw new IllegalArgumentException("Unsupported event schema");
            }
        }
    }
}
