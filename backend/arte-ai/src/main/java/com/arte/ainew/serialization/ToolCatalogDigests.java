package com.arte.ainew.serialization;

import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.tool.ToolDefinition;

import java.util.TreeMap;

/**
 * 工具产品契约摘要 v1：集合排序、JSON 数组保序、十进制精确，排除 checksum 自身。
 * Schema 摘要覆盖标准 JSON 原文；不使用内部 StructuredValue 包装作为 Schema 内容。
 * 提供者版本和执行目标由 ToolDefinitionRevision 的存储完整性另行保护，不冒充发布计划摘要。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 13:52 ✾
 */
public final class ToolCatalogDigests {

    private ToolCatalogDigests() {
    }

    public static String schema(StructuredValue.ObjectValue document) {
        return CanonicalJson.digest(jsonValue(document));
    }

    /**
     * 标准 JSON 配置摘要，保留数组顺序及显式 null，不含内部类型包装。
     */
    public static String configuration(StructuredValue.ObjectValue configuration) {
        return CanonicalJson.digest(jsonValue(configuration));
    }

    /**
     * 用当前服务器配置容量复核旧固定材料；与工具 arguments 的字节上限相互独立。
     */
    public static long configurationBytes(StructuredValue.ObjectValue configuration) {
        return CanonicalJson.encode(jsonValue(configuration)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    public static String tool(ToolDefinition tool) {
        var risk = tool.risk();
        var fields = CanonicalJson.fields(
                "definition", tool.definition(), "providerId", tool.providerId(), "title", tool.title(),
                "description", tool.description(), "tags", tool.tags().stream().sorted().toList(),
                "capability", tool.capability(), "inputSchema", tool.inputSchema(), "outputSchema", tool.outputSchema(),
                "configurationSchema", tool.configurationSchema(), "defaultConfiguration", jsonValue(tool.defaultConfiguration()),
                "capabilities", CanonicalJson.fields("features", tool.capabilities().features().stream().map(Enum::name).sorted().toList(),
                        "outcomeQuerySupported", tool.capabilities().outcomeQuerySupported()),
                "risk", CanonicalJson.fields("sideEffect", risk.sideEffect(), "destructive", risk.destructive(),
                        "idempotency", risk.idempotency(), "requiredScopes", risk.requiredScopes().stream().sorted().toList(),
                        "allowedNetworkOrigins", risk.allowedNetworkOrigins().stream().map(Object::toString).sorted().toList()),
                "defaultPolicy", tool.defaultPolicy());
        if (tool.price() != null) fields.put("price", tool.price());
        return CanonicalJson.digest(fields);
    }

    /**
     * 受控 JSON 值树到标准 JSON 数据；仅投影传入值，不附带 Java 类型或其他工件。
     */
    public static Object jsonValue(StructuredValue value) {
        return switch (value) {
            case StructuredValue.ObjectValue object -> {
                var map = new TreeMap<String, Object>();
                object.fields().forEach((name, child) -> map.put(name, jsonValue(child)));
                yield map;
            }
            case StructuredValue.ArrayValue array ->
                    array.values().stream().map(ToolCatalogDigests::jsonValue).toList();
            case StructuredValue.StringValue string -> string.value();
            case StructuredValue.NumberValue number -> number.value();
            case StructuredValue.BooleanValue bool -> bool.value();
            case StructuredValue.NullValue ignored -> null;
        };
    }
}
