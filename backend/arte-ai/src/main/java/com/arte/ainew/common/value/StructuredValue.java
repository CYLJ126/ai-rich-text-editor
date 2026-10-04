package com.arte.ainew.common.value;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 深度不可变的 JSON 值树，不接受任意 Object、SDK 对象或可变 Map 值。
 * 空值使用 NullValue.INSTANCE；传输编解码器映射为标准 JSON，而非 Java 类名。
 * 结构有界不表示符合工具／输出 Schema，Schema 仍须在调用与结果校验边界验证。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public sealed interface StructuredValue extends Serializable permits StructuredValue.ObjectValue,
        StructuredValue.ArrayValue, StructuredValue.StringValue, StructuredValue.NumberValue,
        StructuredValue.BooleanValue, StructuredValue.NullValue {

    int MAX_NODES = 10_000;
    int MAX_DEPTH = 32;

    record ObjectValue(Map<String, StructuredValue> fields) implements StructuredValue {
        public ObjectValue {
            Objects.requireNonNull(fields, "fields");
            ContractChecks.range(fields.size(), "fields.size", 0, ContractChecks.MAX_ITEMS);
            fields.keySet().forEach(key -> ContractChecks.id(key, "field name"));
            fields = Map.copyOf(fields);
            validateChildren(fields.values(), fields.keySet().stream().mapToLong(String::length).sum());
        }
    }

    record ArrayValue(List<StructuredValue> values) implements StructuredValue {
        public ArrayValue {
            values = ContractChecks.list(values, "values", 0, ContractChecks.MAX_ITEMS);
            validateChildren(values, 0);
        }
    }

    record StringValue(String value) implements StructuredValue {
        public StringValue {
            Objects.requireNonNull(value, "value");
            ContractChecks.range(value.length(), "value.length", 0, ContractChecks.MAX_TEXT_CHARS);
        }
    }

    record NumberValue(BigDecimal value) implements StructuredValue {
        public NumberValue {
            Objects.requireNonNull(value, "value");
            value = value.stripTrailingZeros();
            ContractChecks.range(value.precision(), "number precision", 1, 128);
            ContractChecks.range(value.scale(), "number scale", -128, 128);
        }
    }

    record BooleanValue(boolean value) implements StructuredValue {
    }

    enum NullValue implements StructuredValue {INSTANCE}

    /**
     * 用于调用／消息／结果的累计容量校验，不等同于 JSON 编码字节数。
     */
    default long characterCount() {
        return switch (this) {
            case ObjectValue object -> validateChildren(object.fields().values(),
                    object.fields().keySet().stream().mapToLong(String::length).sum());
            case ArrayValue array -> validateChildren(array.values(), 0);
            case StringValue string -> string.value().length();
            case NumberValue number -> number.value().toString().length();
            case BooleanValue ignored -> 5;
            case NullValue ignored -> 4;
        };
    }

    /**
     * 每个构造出的容器均限制整棵树；不会在深嵌套输入上递归溢出。
     */
    private static long validateChildren(Iterable<StructuredValue> children, long keyCharacters) {
        record Node(StructuredValue value, int depth) {
        }
        var pending = new ArrayDeque<Node>();
        children.forEach(value -> pending.add(new Node(value, 2)));
        int nodes = 1;
        long characters = keyCharacters;
        while (!pending.isEmpty()) {
            Node node = pending.removeFirst();
            ContractChecks.require(++nodes <= MAX_NODES && node.depth() <= MAX_DEPTH,
                    "Structured value exceeds node or depth limit");
            if (node.value() instanceof ObjectValue object) {
                for (var entry : object.fields().entrySet()) {
                    characters += entry.getKey().length();
                    pending.add(new Node(entry.getValue(), node.depth() + 1));
                }
            } else if (node.value() instanceof ArrayValue array) {
                array.values().forEach(value -> pending.add(new Node(value, node.depth() + 1)));
            } else if (node.value() instanceof StringValue string) {
                characters += string.value().length();
            } else if (node.value() instanceof NumberValue number) {
                characters += number.value().toString().length();
            } else {
                characters += 5;
            }
            ContractChecks.require(characters <= ContractChecks.MAX_TEXT_CHARS,
                    "Structured value exceeds character limit");
        }
        return characters;
    }
}
