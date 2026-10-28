package com.arte.ainew.serialization;

import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.core.pojo.reference.DefinitionRef;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.*;
import tools.jackson.databind.module.SimpleModule;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 绑定快照的封闭 JSON 映射；只识别 LOCAL／REMOTE，不启用 Java 类名或全局多态。
 * 内部新快照写类型化目标；现有平台模型目录继续写旧远端形状，避免改变其 v1 API／摘要格式。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17
 */
public final class ResolvedBindingJson {
    private static final Set<String> TYPED_FIELDS = Set.of("definition", "capability", "target", "contextWindowTokens", "rate");
    private static final Set<String> LEGACY_FIELDS = Set.of("definition", "capability", "connection", "remoteOperation", "contextWindowTokens", "rate");

    private ResolvedBindingJson() {
    }

    /**
     * 仅注册到明确的持久化／控制面 mapper，不改变应用全局 JSON。
     */
    public static SimpleModule module(boolean typedWriter) {
        var module = new SimpleModule("arte-resolved-binding");
        module.addSerializer(ResolvedBinding.class, new ValueSerializer<>() {
            @Override
            public void serialize(ResolvedBinding value, JsonGenerator generator, SerializationContext context) {
                var fields = new TreeMap<String, Object>();
                fields.put("definition", value.definition());
                fields.put("capability", value.capability());
                fields.put("contextWindowTokens", value.contextWindowTokens());
                fields.put("rate", value.rate());
                if (!typedWriter) {
                    var remote = value.requireRemoteTarget();
                    fields.put("connection", remote.connection());
                    fields.put("remoteOperation", remote.operation());
                } else {
                    fields.put("target", switch (value.target()) {
                        case ExecutionTarget.Local local -> Map.of("kind", "LOCAL", "adapter", local.adapter());
                        case ExecutionTarget.Remote remote ->
                                Map.of("kind", "REMOTE", "connection", remote.connection(), "operation", remote.operation());
                    });
                }
                context.writeValue(generator, fields);
            }
        });
        module.addDeserializer(ResolvedBinding.class, new ValueDeserializer<>() {
            @Override
            public ResolvedBinding deserialize(JsonParser parser, DeserializationContext context) {
                JsonNode body = context.readTree(parser);
                boolean typed = body.has("target");
                checkFields(body, typed ? TYPED_FIELDS : LEGACY_FIELDS);
                var definition = reference(body, "definition", false);
                var capability = context.readTreeAsValue(required(body, "capability"), CapabilityDescriptor.class);
                var rate = reference(body, "rate", true);
                Long window = null;
                if (body.hasNonNull("contextWindowTokens")) {
                    var node = body.get("contextWindowTokens");
                    if (!node.isIntegralNumber() || !node.canConvertToLong()) throw invalid();
                    window = node.longValue();
                }
                ExecutionTarget target;
                if (typed) {
                    var node = required(body, "target");
                    target = switch (text(node, "kind")) {
                        case "LOCAL" -> {
                            checkFields(node, Set.of("kind", "adapter"));
                            yield new ExecutionTarget.Local(reference(node, "adapter", false));
                        }
                        case "REMOTE" -> {
                            checkFields(node, Set.of("kind", "connection", "operation"));
                            yield new ExecutionTarget.Remote(reference(node, "connection", false), text(node, "operation"));
                        }
                        default -> throw invalid();
                    };
                } else {
                    target = new ExecutionTarget.Remote(reference(body, "connection", false), text(body, "remoteOperation"));
                }
                return new ResolvedBinding(definition, capability, target, window, rate);
            }
        });
        return module;
    }

    private static DefinitionRef reference(JsonNode body, String name, boolean optional) {
        if (optional && !body.hasNonNull(name)) return null;
        var node = required(body, name);
        checkFields(node, Set.of("type", "id", "version"));
        return new DefinitionRef(text(node, "type"), text(node, "id"), text(node, "version"));
    }

    private static void checkFields(JsonNode node, Set<String> allowed) {
        if (!node.isObject() || !allowed.containsAll(node.propertyNames())) throw invalid();
    }

    private static JsonNode required(JsonNode body, String name) {
        if (!body.isObject() || !body.hasNonNull(name)) throw invalid();
        return body.get(name);
    }

    private static String text(JsonNode body, String name) {
        var value = required(body, name);
        if (!value.isString()) throw invalid();
        return value.asString();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid resolved binding snapshot");
    }
}
