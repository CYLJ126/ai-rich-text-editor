package com.arte.app.platform.persistence;

import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.pojo.access.GrantRecipient;
import com.arte.core.pojo.access.GrantTarget;
import com.arte.core.pojo.platform.CommandScope;
import com.arte.core.pojo.platform.Scope;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.*;
import tools.jackson.databind.module.SimpleModule;

import java.util.Map;
import java.util.Set;

/**
 * 封闭 kind 白名单
 * <p>
 * 仅用于平台存储，不启用类名多态或修改全局 HTTP mapper。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 17:50 ✾
 */
final class ResourceAclJson {

    private ResourceAclJson() {
    }

    static SimpleModule module() {
        var module = new SimpleModule("platform-resource-acl");
        module.addSerializer(GrantRecipient.class, new ValueSerializer<>() {
            @Override
            public void serialize(GrantRecipient value, JsonGenerator out, SerializationContext context) {
                context.writeValue(out, switch (value) {
                    case GrantRecipient.Subject s -> Map.of("kind", "SUBJECT", "subjectId", s.subjectId());
                    case GrantRecipient.SpaceRole s ->
                            Map.of("kind", "SPACE_ROLE", "scope", s.scope(), "role", s.role());
                    case GrantRecipient.Space s -> Map.of("kind", "WORKSPACE", "scope", s.scope());
                });
            }
        });
        module.addDeserializer(GrantRecipient.class, new ValueDeserializer<>() {
            @Override
            public GrantRecipient deserialize(JsonParser in, DeserializationContext context) {
                var node = context.readTree(in);
                return switch (text(node, "kind")) {
                    case "SUBJECT" -> {
                        fields(node, "kind", "subjectId");
                        yield new GrantRecipient.Subject(text(node, "subjectId"));
                    }
                    case "SPACE_ROLE" -> {
                        fields(node, "kind", "scope", "role");
                        yield new GrantRecipient.SpaceRole(context.readTreeAsValue(node.get("scope"), Scope.class),
                                context.readTreeAsValue(node.get("role"), WorkspaceRoleEnum.class));
                    }
                    case "WORKSPACE" -> {
                        fields(node, "kind", "scope");
                        yield new GrantRecipient.Space(context.readTreeAsValue(node.get("scope"), Scope.class));
                    }
                    default -> throw new IllegalArgumentException("Unknown grant recipient kind");
                };
            }
        });
        module.addSerializer(GrantTarget.class, new ValueSerializer<>() {
            @Override
            public void serialize(GrantTarget value, JsonGenerator out, SerializationContext context) {
                context.writeValue(out, switch (value) {
                    case GrantTarget.Subject s ->
                            Map.of("kind", "SUBJECT", "subjectId", s.subjectId(), "scope", s.scope(),
                                    "membershipGeneration", s.membershipGeneration());
                    case GrantTarget.SpaceRole s -> Map.of("kind", "SPACE_ROLE", "scope", s.scope(), "role", s.role());
                    case GrantTarget.Space s -> Map.of("kind", "WORKSPACE", "scope", s.scope());
                });
            }
        });
        module.addDeserializer(GrantTarget.class, new ValueDeserializer<>() {
            @Override
            public GrantTarget deserialize(JsonParser in, DeserializationContext context) {
                var node = context.readTree(in);
                return switch (text(node, "kind")) {
                    case "SUBJECT" -> {
                        fields(node, "kind", "subjectId", "scope", "membershipGeneration");
                        var generation = node.get("membershipGeneration");
                        if (!generation.isIntegralNumber() || !generation.canConvertToLong())
                            throw new IllegalArgumentException("Invalid member generation");
                        yield new GrantTarget.Subject(text(node, "subjectId"), context.readTreeAsValue(node.get("scope"),
                                CommandScope.class), generation.longValue());
                    }
                    case "SPACE_ROLE" -> {
                        fields(node, "kind", "scope", "role");
                        yield new GrantTarget.SpaceRole(context.readTreeAsValue(node.get("scope"), Scope.class),
                                context.readTreeAsValue(node.get("role"), WorkspaceRoleEnum.class));
                    }
                    case "WORKSPACE" -> {
                        fields(node, "kind", "scope");
                        yield new GrantTarget.Space(context.readTreeAsValue(node.get("scope"), Scope.class));
                    }
                    default -> throw new IllegalArgumentException("Unknown grant target kind");
                };
            }
        });
        return module;
    }

    private static String text(JsonNode node, String name) {
        var value = node.get(name);
        if (value == null || !value.isString()) throw new IllegalArgumentException("Missing ACL field");
        return value.asString();
    }

    private static void fields(JsonNode node, String... fields) {
        if (!node.isObject() || !node.propertyNames().equals(Set.of(fields)))
            throw new IllegalArgumentException("Unexpected ACL fields");
    }
}
