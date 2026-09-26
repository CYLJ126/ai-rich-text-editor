package com.arte.ai.service.tool;

import com.arte.ai.api.tool.ToolDefinitionValidator;
import com.arte.ai.common.enums.tool.ToolRiskLevelEnum;
import com.arte.ai.common.exception.ToolDefinitionValidationException;
import com.arte.ai.pojo.tool.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 默认工具定义校验器
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Component
@RequiredArgsConstructor
public class DefaultToolDefinitionValidator implements ToolDefinitionValidator {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,99}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;
    private static final int MAX_OUTPUT_TOKENS = 32768;
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(30);

    private final ObjectMapper objectMapper;

    @Override
    public void validate(ToolDefinition definition) {
        List<String> violations = new ArrayList<>();
        if (definition == null) {
            throw new ToolDefinitionValidationException(List.of("tool definition must not be null"));
        }

        validateReference(definition.reference(), violations);
        validateText("title", definition.title(), 2, MAX_TITLE_LENGTH, violations);
        validateText("description", definition.description(), 8, MAX_DESCRIPTION_LENGTH, violations);
        validateSchema("inputSchema", definition.inputSchema(), true, violations);
        validateSchema("outputSchema", definition.outputSchema(), false, violations);
        validatePolicy(definition, violations);
        validateRisk(definition, violations);

        if (definition.tags().stream().anyMatch(tag -> tag == null || tag.isBlank() || tag.length() > 64)) {
            violations.add("tags must be non-blank and no longer than 64 characters");
        }
        if (definition.tags().size() > 32) {
            violations.add("tags must not contain more than 32 entries");
        }
        if (!violations.isEmpty()) {
            throw new ToolDefinitionValidationException(violations);
        }
    }

    private void validateReference(ToolReference reference, List<String> violations) {
        if (reference == null) {
            violations.add("reference must not be null");
            return;
        }
        if (!IDENTIFIER.matcher(reference.namespace()).matches()) {
            violations.add("namespace must match " + IDENTIFIER.pattern());
        }
        if (!IDENTIFIER.matcher(reference.name()).matches()) {
            violations.add("name must match " + IDENTIFIER.pattern());
        }
        if (!VERSION.matcher(reference.version()).matches()) {
            violations.add("version must match " + VERSION.pattern());
        }
    }

    private void validateSchema(String field, ToolSchema schema, boolean input, List<String> violations) {
        if (schema == null) {
            violations.add(field + " must not be null");
            return;
        }
        if (schema.dialect().isBlank()) {
            violations.add(field + ".dialect must not be blank");
        }
        try {
            JsonNode root = objectMapper.readTree(schema.schema());
            if (root == null || !root.isObject()) {
                violations.add(field + " must be a JSON object");
                return;
            }
            if (input && !"object".equals(root.path("type").asText())) {
                violations.add(field + " root type must be object");
            }
            if (input) {
                validateInputProperties(root, violations);
            }
        } catch (Exception exception) {
            violations.add(field + " is not valid JSON: " + exception.getMessage());
        }
    }

    private void validateInputProperties(JsonNode root, List<String> violations) {
        JsonNode properties = root.path("properties");
        if (!properties.isObject()) {
            violations.add("inputSchema.properties must be an object");
            return;
        }
        Set<String> propertyNames = new HashSet<>();
        properties.properties().forEach(entry -> {
            String name = entry.getKey();
            JsonNode property = entry.getValue();
            propertyNames.add(name);
            if (!IDENTIFIER.matcher(name).matches()) {
                violations.add("input parameter name is ambiguous or invalid: " + name);
            }
            if (!property.isObject()) {
                violations.add("input parameter schema must be an object: " + name);
                return;
            }
            if (property.path("type").asText().isBlank()
                    && property.path("$ref").asText().isBlank()
                    && !property.path("oneOf").isArray()
                    && !property.path("anyOf").isArray()) {
                violations.add("input parameter must declare type, $ref, oneOf or anyOf: " + name);
            }
        });

        JsonNode required = root.path("required");
        if (!required.isMissingNode() && !required.isArray()) {
            violations.add("inputSchema.required must be an array");
        } else if (required.isArray()) {
            required.forEach(item -> {
                String name = item.asText();
                if (!propertyNames.contains(name)) {
                    violations.add("required input parameter is not declared in properties: " + name);
                }
            });
        }
    }

    private void validatePolicy(ToolDefinition definition, List<String> violations) {
        if (definition.capabilities() == null) {
            violations.add("capabilities must not be null");
            return;
        }
        ToolExecutionPolicy policy = definition.defaultPolicy();
        if (policy == null) {
            violations.add("defaultPolicy must not be null");
            return;
        }
        if (!definition.capabilities().executionModes().contains(policy.executionMode())) {
            violations.add("default execution mode must be included in capabilities.executionModes");
        }
        if (policy.timeout().compareTo(MAX_TIMEOUT) > 0) {
            violations.add("default timeout must not exceed " + MAX_TIMEOUT);
        }
        if (policy.maxOutputTokens() <= 0 || policy.maxOutputTokens() > MAX_OUTPUT_TOKENS) {
            violations.add("maxOutputTokens must be between 1 and " + MAX_OUTPUT_TOKENS);
        }
    }

    private void validateRisk(ToolDefinition definition, List<String> violations) {
        ToolRiskProfile risk = definition.riskProfile();
        if (risk == null) {
            violations.add("riskProfile must not be null");
            return;
        }
        if ((risk.destructive() || risk.level() == ToolRiskLevelEnum.CRITICAL)
                && !definition.defaultPolicy().requiresApproval()) {
            violations.add("destructive or critical tools must require approval by default");
        }
        if (risk.openWorld() && risk.allowedNetworkTargets().isEmpty()) {
            violations.add("open-world tools must declare allowedNetworkTargets");
        }
    }

    private void validateText(String field, String value, int minimumLength, int maximumLength,
                              List<String> violations) {
        if (value == null || value.isBlank()) {
            violations.add(field + " must not be blank");
            return;
        }
        String normalized = value.trim();
        if (normalized.length() < minimumLength || normalized.length() > maximumLength) {
            violations.add(field + " length must be between " + minimumLength + " and " + maximumLength);
        }
    }
}
