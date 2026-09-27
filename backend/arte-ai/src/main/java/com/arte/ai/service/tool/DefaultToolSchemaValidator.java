package com.arte.ai.service.tool;

import com.arte.ai.api.tool.ToolSchemaValidator;
import com.arte.ai.pojo.tool.ToolSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 轻量 JSON Schema 运行时校验器。
 *
 * <p>一期覆盖工具参数最常用的 object、array、string、number、integer、boolean、null、
 * required、enum 和 additionalProperties。复杂 Schema 可通过替换本端口接入专业校验器。</p>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Component
@RequiredArgsConstructor
public class DefaultToolSchemaValidator implements ToolSchemaValidator {

    private final ObjectMapper objectMapper;

    @Override
    public void validate(ToolSchema schema, Object value, String valueName) {
        if (schema == null) {
            throw new IllegalArgumentException(valueName + " schema must not be null");
        }
        try {
            JsonNode schemaNode = objectMapper.readTree(schema.schema());
            JsonNode valueNode = objectMapper.valueToTree(value);
            List<String> violations = new ArrayList<>();
            validateNode(schemaNode, valueNode, "$", violations);
            if (!violations.isEmpty()) {
                throw new IllegalArgumentException(valueName + " does not match schema: "
                        + String.join("; ", violations));
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("failed to validate " + valueName, exception);
        }
    }

    private void validateNode(JsonNode schema, JsonNode value, String path, List<String> violations) {
        if (schema == null || !schema.isObject()) {
            return;
        }
        JsonNode enumValues = schema.path("enum");
        if (enumValues.isArray() && !contains(enumValues, value)) {
            violations.add(path + " is not one of the allowed values");
        }
        String type = schema.path("type").asText();
        if (!type.isBlank() && !matchesType(type, value)) {
            violations.add(path + " must be " + type);
            return;
        }
        if (value != null && value.isObject()) {
            validateObject(schema, value, path, violations);
        } else if (value != null && value.isArray()) {
            if (schema.path("maxItems").canConvertToInt() && value.size() > schema.path("maxItems").asInt()) {
                violations.add(path + " contains too many items");
            }
            JsonNode items = schema.path("items");
            if (items.isObject()) {
                for (int index = 0; index < value.size(); index++) {
                    validateNode(items, value.get(index), path + "[" + index + "]", violations);
                }
            }
        } else if (value != null && value.isTextual()) {
            int length = value.asText().length();
            if (schema.path("maxLength").canConvertToInt()
                    && length > schema.path("maxLength").asInt()) {
                violations.add(path + " exceeds maxLength");
            }
            if (schema.path("minLength").canConvertToInt()
                    && length < schema.path("minLength").asInt()) {
                violations.add(path + " is shorter than minLength");
            }
        }
    }

    private void validateObject(JsonNode schema, JsonNode value, String path, List<String> violations) {
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            required.forEach(name -> {
                if (!value.has(name.asText())) {
                    violations.add(path + "." + name.asText() + " is required");
                }
            });
        }
        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            properties.properties().forEach(entry -> {
                if (value.has(entry.getKey())) {
                    validateNode(entry.getValue(), value.get(entry.getKey()),
                            path + "." + entry.getKey(), violations);
                }
            });
            boolean rejectUnknown = "$".equals(path)
                    ? !schema.path("additionalProperties").isBoolean()
                    || !schema.path("additionalProperties").asBoolean()
                    : schema.path("additionalProperties").isBoolean()
                    && !schema.path("additionalProperties").asBoolean();
            if (rejectUnknown) {
                Map<String, JsonNode> declared = new java.util.HashMap<>();
                properties.properties().forEach(entry -> declared.put(entry.getKey(), entry.getValue()));
                value.properties().forEach(entry -> {
                    if (!declared.containsKey(entry.getKey())) {
                        violations.add(path + "." + entry.getKey() + " is not allowed");
                    }
                });
            }
        }
    }

    private boolean matchesType(String type, JsonNode value) {
        if (value == null) {
            return "null".equals(type);
        }
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private boolean contains(JsonNode array, JsonNode value) {
        for (JsonNode candidate : array) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
