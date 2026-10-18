package com.arte.ainew.web;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.tool.ToolBindingSettings;
import com.arte.ainew.pojo.tool.ToolExecutionPolicy;
import com.arte.ainew.pojo.tool.ToolReleaseCommand;
import com.arte.ainew.web.request.ToolManagementRequests.Policy;
import com.arte.ainew.web.request.ToolManagementRequests.Review;
import com.arte.ainew.web.request.ToolManagementRequests.Settings;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.*;

/**
 * 标准 JSON 与有界值树的显式转换；HTTP 配置阻止常见凭据键，不反射加载任何类型。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
public final class ToolManagementValues {

    private ToolManagementValues() {
    }

    /**
     * 仅辅助拦截明显凭据字段，不能代替来源审核、Secret 使用授权或完整防泄漏机制。
     */
    private static final Set<String> SENSITIVE_KEYS = Set.of("password", "passwd", "secret", "apikey", "accesstoken", "refreshtoken",
            "authorization", "privatekey", "clientsecret", "token");

    public static StructuredValue.ObjectValue object(JsonNode node, boolean configuration) {
        ContractChecks.require(node != null && node.isObject(), "Expected a JSON object");
        var value = (StructuredValue.ObjectValue) read(node, 0, new int[]{0}, configuration);
        if (configuration)
            ContractChecks.require(com.arte.ainew.serialization.ToolCatalogDigests.configurationBytes(value) <= 1_000_000, "Configuration exceeds byte limit");
        return value;
    }

    private static StructuredValue read(JsonNode node, int depth, int[] count, boolean configuration) {
        ContractChecks.require(depth <= 24 && ++count[0] <= 8192, "JSON tree exceeds limit");
        if (node.isObject()) {
            ContractChecks.require(node.size() <= 256, "Too many object fields");
            var fields = new LinkedHashMap<String, StructuredValue>();
            node.properties().forEach(entry -> {
                String name = entry.getKey();
                if (configuration && SENSITIVE_KEYS.contains(name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("Credential fields are not configuration");
                fields.put(name, read(entry.getValue(), depth + 1, count, configuration));
            });
            return new StructuredValue.ObjectValue(fields);
        }
        if (node.isArray()) {
            ContractChecks.require(node.size() <= 256, "Too many array elements");
            var values = new ArrayList<StructuredValue>();
            node.forEach(value -> values.add(read(value, depth + 1, count, configuration)));
            return new StructuredValue.ArrayValue(values);
        }
        if (node.isString()) return new StructuredValue.StringValue(node.asString());
        if (node.isNumber()) return new StructuredValue.NumberValue(new BigDecimal(node.asString()));
        if (node.isBoolean()) return new StructuredValue.BooleanValue(node.asBoolean());
        if (node.isNull()) return StructuredValue.NullValue.INSTANCE;
        throw new IllegalArgumentException("Unsupported JSON value");
    }

    public static Object json(StructuredValue value) {
        return switch (value) {
            case StructuredValue.ObjectValue object -> {
                var fields = new TreeMap<String, Object>();
                object.fields().forEach((key, child) -> fields.put(key, json(child)));
                yield fields;
            }
            case StructuredValue.ArrayValue array -> array.values().stream().map(ToolManagementValues::json).toList();
            case StructuredValue.StringValue text -> text.value();
            case StructuredValue.NumberValue number -> number.value();
            case StructuredValue.BooleanValue bool -> bool.value();
            case StructuredValue.NullValue ignored -> null;
        };
    }

    public static ToolBindingSettings settings(Settings value) {
        Objects.requireNonNull(value);
        return new ToolBindingSettings(value.tool(), value.versionPolicy(), value.name(), Objects.requireNonNull(value.enabled()),
                object(value.configuration(), true), value.secrets() == null ? Map.of() : value.secrets(), policy(value.policy()),
                value.networkOrigins() == null ? null : value.networkOrigins().stream().map(URI::create).collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    public static ToolExecutionPolicy policy(Policy value) {
        return value == null ? null : new ToolExecutionPolicy(Duration.ofMillis(value.timeoutMillis()), value.maxAttempts(), Duration.ofMillis(value.retryBackoffMillis()),
                value.maxInputBytes(), value.maxOutputBytes(), value.approvalMode());
    }

    public static ToolReleaseCommand.Review review(Review value) {
        return new ToolReleaseCommand.Review(value.examples().stream().map(example -> {
            var input = object(example.input(), false);
            var output = read(example.output(), 0, new int[]{0}, false);
            ContractChecks.require(com.arte.ainew.serialization.ToolCatalogDigests.configurationBytes(input) <= ToolReleaseCommand.MAX_EXAMPLE_BYTES
                            && com.arte.ainew.serialization.CanonicalJson.encode(json(output)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= ToolReleaseCommand.MAX_EXAMPLE_BYTES,
                    "Review example exceeds byte limit");
            return new ToolReleaseCommand.Example(input, output);
        }).toList(),
                value.riskEvidence(), value.idempotencyEvidence(), value.minimumApproval(), value.compatibleFrom(), value.compatibilityEvidence());
    }

    public static long version(String value) {
        ContractChecks.require(value != null && value.matches("0|[1-9][0-9]{0,18}"), "Invalid management version");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Management version exceeds limit");
        }
    }

    public static int size(Integer value) {
        return value == null ? 20 : value;
    }
}
