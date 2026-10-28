package com.arte.ainew.infrastructure.provider.deepseek;

import com.arte.ainew.application.tool.binding.ToolBindingConstraints;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.infrastructure.http.GenerationException;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.generation.ToolCall;
import com.arte.ainew.pojo.tool.ToolSetSnapshot;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.core.pojo.value.StructuredValue;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.*;

/**
 * 单轮工具协议：只使用显式快照，校验 Schema 语义及消息配对，不读取目录或执行器。
 * 参数使用独立严格解析器，拒绝重复键、尾随 JSON、深嵌套及不精确数字。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 15:20 ✾
 */
final class DeepSeekToolProtocol {
    private static final JsonMapper ARGUMENTS = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24)
                            .maxStringLength(ContractChecks.MAX_TEXT_CHARS).maxNumberLength(128).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                    DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    private final ToolSetSnapshot snapshot;
    private final Map<String, ToolSchemaValidator.CompiledSchema> schemas = new HashMap<>();
    private final Set<String> historyIds = new HashSet<>();

    DeepSeekToolProtocol(GatewayCall<GenerationRequest> call, ToolSetSnapshot snapshot) {
        this.snapshot = snapshot;
        var input = call.request().input();
        if (snapshot == null) {
            if (!input.tools().isEmpty() || input.messages().stream().anyMatch(m -> m.role() == ChatMessage.Role.TOOL || !m.toolCalls().isEmpty()))
                throw GenerationException.beforeSend("TOOL_SET_REQUIRED");
            return;
        }
        if (!call.binding().capability().features().contains(CapabilityDescriptor.Feature.TOOL_CALLS)
                || !snapshot.owner().equals(ExecutionOwner.from(call.request().context()))
                || !snapshot.owner().equals(ExecutionOwner.from(call.runtime().execution()))
                || !snapshot.descriptions().equals(input.tools()))
            throw GenerationException.beforeSend("TOOL_SET_MISMATCH");
        try {
            var validator = new ToolSchemaValidator();
            for (var entry : snapshot.entries()) {
                var schema = entry.inputSchema();
                ContractChecks.require(schema.checksum().equals(ToolCatalogDigests.schema(schema.document())), "Schema checksum mismatch");
                ContractChecks.require(ToolBindingConstraints.intersect(entry.binding().effectivePolicy(), entry.policy()).equals(entry.policy()),
                        "Tool set policy cannot loosen binding policy");
                ContractChecks.require(call.runtime().execution().authorization().scopes().containsAll(entry.binding().tool().tool().risk().requiredScopes()),
                        "Tool set exceeds runtime scopes");
                schemas.put(entry.modelName(), validator.compile(schema.definition(), schema.document(), ToolSchemaValidator.Purpose.INPUT));
            }
        } catch (RuntimeException invalid) {
            throw GenerationException.beforeSend("INVALID_TOOL_SET");
        }
    }

    List<DeepSeekWire.Tool> declarations() {
        if (snapshot == null) return null;
        return snapshot.entries().stream().map(entry -> new DeepSeekWire.Tool("function", new DeepSeekWire.FunctionDefinition(
                entry.modelName(), entry.description().description(), ToolCatalogDigests.jsonValue(providerSchema(entry.inputSchema().document(), true))))).toList();
    }

    List<DeepSeekWire.Message> messages(List<ChatMessage> messages) {
        var pending = new HashSet<String>();
        var result = new ArrayList<DeepSeekWire.Message>();
        for (var message : messages) {
            if (message.content().stream().anyMatch(part -> !(part instanceof ChatMessage.Text)))
                throw GenerationException.beforeSend("UNSUPPORTED_GENERATION_FEATURE");
            String content = message.content().stream().map(p -> ((ChatMessage.Text) p).text()).reduce("", String::concat);
            if (message.role() == ChatMessage.Role.TOOL) {
                if (!pending.remove(message.toolCallId()))
                    throw GenerationException.beforeSend("INVALID_TOOL_MESSAGE_PAIRING");
            } else if (!pending.isEmpty()) {
                throw GenerationException.beforeSend("INVALID_TOOL_MESSAGE_PAIRING");
            }
            var proposals = new ArrayList<DeepSeekWire.ToolProposal>();
            for (var proposal : message.toolCalls()) {
                if (snapshot == null || !historyIds.add(proposal.callId()))
                    throw GenerationException.beforeSend("INVALID_TOOL_MESSAGE_PAIRING");
                try {
                    var entry = snapshot.tool(proposal.tool());
                    validate(entry, proposal.arguments());
                    proposals.add(new DeepSeekWire.ToolProposal(null, proposal.callId(), "function", new DeepSeekWire.Function(
                            entry.modelName(), CanonicalJson.encode(ToolCatalogDigests.jsonValue(proposal.arguments())))));
                    pending.add(proposal.callId());
                } catch (RuntimeException invalid) {
                    throw GenerationException.beforeSend("INVALID_TOOL_HISTORY");
                }
            }
            result.add(new DeepSeekWire.Message(message.role().name().toLowerCase(Locale.ROOT),
                    content.isEmpty() && !proposals.isEmpty() ? null : content, proposals.isEmpty() ? null : List.copyOf(proposals), message.toolCallId()));
        }
        if (!pending.isEmpty()) throw GenerationException.beforeSend("INVALID_TOOL_MESSAGE_PAIRING");
        return List.copyOf(result);
    }

    ToolSetSnapshot.Entry named(String name) {
        if (snapshot == null) throw GenerationException.output("UNKNOWN_MODEL_TOOL");
        try {
            return snapshot.named(name);
        } catch (IllegalArgumentException invalid) {
            throw GenerationException.output("UNKNOWN_MODEL_TOOL");
        }
    }

    void requireNewId(String id) {
        if (historyIds.contains(id)) throw GenerationException.output("DUPLICATE_TOOL_CALL_ID");
    }

    ToolCall complete(String id, String name, String arguments) {
        var entry = named(name);
        try {
            var value = value(ARGUMENTS.readValue(arguments, Object.class), 0, new int[]{0});
            if (!(value instanceof StructuredValue.ObjectValue object))
                throw new IllegalArgumentException("Arguments must be an object");
            validate(entry, object);
            return new ToolCall(id, entry.binding().tool().tool().definition(), object);
        } catch (RuntimeException invalid) {
            throw GenerationException.output("INVALID_TOOL_ARGUMENTS");
        }
    }

    private void validate(ToolSetSnapshot.Entry entry, StructuredValue.ObjectValue arguments) {
        ContractChecks.require(schemas.get(entry.modelName()).validate(arguments, entry.policy().maxInputBytes()).valid(), "Invalid tool arguments");
    }

    private static StructuredValue value(Object raw, int depth, int[] nodes) {
        ContractChecks.require(depth <= 24 && ++nodes[0] <= 8192, "Tool arguments structure limit");
        if (raw == null) return StructuredValue.NullValue.INSTANCE;
        if (raw instanceof Map<?, ?> map) {
            var fields = new TreeMap<String, StructuredValue>();
            map.forEach((k, v) -> fields.put((String) k, value(v, depth + 1, nodes)));
            return new StructuredValue.ObjectValue(fields);
        }
        if (raw instanceof List<?> list)
            return new StructuredValue.ArrayValue(list.stream().map(v -> value(v, depth + 1, nodes)).toList());
        if (raw instanceof String text) return new StructuredValue.StringValue(text);
        if (raw instanceof Boolean flag) return new StructuredValue.BooleanValue(flag);
        if (raw instanceof Number number) return new StructuredValue.NumberValue(new BigDecimal(number.toString()));
        throw new IllegalArgumentException("Invalid tool value");
    }

    /**
     * ARTE 对象默认拒绝额外字段；显式补齐后外发，避免供应商按标准 JSON Schema 默认允许。
     */
    private static StructuredValue.ObjectValue providerSchema(StructuredValue.ObjectValue schema, boolean root) {
        var fields = new TreeMap<>(schema.fields());
        if (root) fields.remove("$schema");
        if (!fields.containsKey("type") || fields.get("type") instanceof StructuredValue.StringValue type && type.value().equals("object"))
            fields.putIfAbsent("additionalProperties", new StructuredValue.BooleanValue(false));
        if (fields.get("properties") instanceof StructuredValue.ObjectValue properties) {
            var children = new TreeMap<String, StructuredValue>();
            properties.fields().forEach((key, child) -> children.put(key, providerSchema((StructuredValue.ObjectValue) child, false)));
            fields.put("properties", new StructuredValue.ObjectValue(children));
        }
        if (fields.get("items") instanceof StructuredValue.ObjectValue items)
            fields.put("items", providerSchema(items, false));
        return new StructuredValue.ObjectValue(fields);
    }
}
