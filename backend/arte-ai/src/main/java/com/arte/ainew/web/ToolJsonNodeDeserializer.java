package com.arte.ainew.web;

import com.arte.ainew.common.validation.ContractChecks;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 管理配置／示例的有界标准 JSON：保留精确十进制、拒绝重复键，避免默认浮点树丢失用户值。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
public final class ToolJsonNodeDeserializer extends ValueDeserializer<JsonNode> {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Override
    public JsonNode deserialize(JsonParser parser, DeserializationContext context) {
        return read(parser, 0, new int[]{0}, new long[]{0});
    }

    @Override
    public JsonNode getNullValue(DeserializationContext context) {
        return NODES.nullNode();
    }

    private JsonNode read(JsonParser parser, int depth, int[] count, long[] characters) {
        ContractChecks.require(depth <= 24 && ++count[0] <= 8192, "JSON tree exceeds limit");
        return switch (parser.currentToken()) {
            case START_OBJECT -> {
                var object = NODES.objectNode();
                int size = 0;
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    ContractChecks.require(parser.currentToken() == JsonToken.PROPERTY_NAME && ++size <= 256, "Invalid object fields");
                    String name = parser.currentName();
                    ContractChecks.id(name, "JSON field");
                    ContractChecks.require(!object.has(name), "Duplicate JSON field");
                    characters[0] += name.length();
                    parser.nextToken();
                    object.set(name, read(parser, depth + 1, count, characters));
                }
                yield object;
            }
            case START_ARRAY -> {
                var array = NODES.arrayNode();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    ContractChecks.require(array.size() < 256, "Too many array items");
                    array.add(read(parser, depth + 1, count, characters));
                }
                yield array;
            }
            case VALUE_STRING -> {
                String value = parser.getString();
                characters[0] += value.length();
                ContractChecks.require(characters[0] <= 1_000_000, "JSON characters exceed limit");
                yield NODES.stringNode(value);
            }
            case VALUE_NUMBER_FLOAT, VALUE_NUMBER_INT -> {
                var value = parser.getDecimalValue().stripTrailingZeros();
                ContractChecks.require(value.precision() <= 128 && value.scale() >= -128 && value.scale() <= 128, "Number exceeds limit");
                yield NODES.numberNode(value);
            }
            case VALUE_TRUE -> NODES.booleanNode(true);
            case VALUE_FALSE -> NODES.booleanNode(false);
            case VALUE_NULL -> NODES.nullNode();
            default -> throw new IllegalArgumentException("Invalid JSON token");
        };
    }
}
