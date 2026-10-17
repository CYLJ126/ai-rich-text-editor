package com.arte.ainew.application.tool.schema;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.common.value.StructuredValue.*;

import java.io.Serial;
import java.math.BigDecimal;
import java.util.*;

/**
 * TOOL 首版 Schema 子集：先编译受信固定版本，再复用不可变校验计划。
 * 不加载 URI、不读取目录、不补写 default，也不代替当前授权或传输层原始字节限制。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17
 */
public final class ToolSchemaValidator {
    /**
     * 项目子集默认关闭对象额外属性，不能冒充完整的 JSON Schema 2020-12 dialect。
     */
    public static final String DIALECT = "urn:arte:ai-tool:schema:v1";
    private static final Set<String> KEYWORDS = Set.of("$schema", "type", "enum", "const", "title", "description",
            "default", "properties", "required", "additionalProperties", "items", "minItems", "maxItems",
            "minLength", "maxLength", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum");
    private static final Set<String> TYPES = Set.of("object", "array", "string", "number", "integer", "boolean", "null");
    private final Limits limits;

    public enum Purpose {INPUT, CONFIGURATION, OUTPUT}

    public enum Code {
        INVALID_SCHEMA, UNSUPPORTED_KEYWORD, LIMIT_EXCEEDED, INVALID_UNICODE,
        TYPE, REQUIRED, ADDITIONAL_PROPERTY, ENUM, CONST, MIN_ITEMS, MAX_ITEMS,
        MIN_LENGTH, MAX_LENGTH, MINIMUM, MAXIMUM, EXCLUSIVE_MINIMUM, EXCLUSIVE_MAXIMUM
    }

    /**
     * JSON Pointer 指向实例及 Schema；错误不回显提交值、枚举候选或配置内容。
     */
    public record Violation(Code code, String instancePointer, String schemaPointer) {
        public Violation {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(instancePointer, "instancePointer");
            Objects.requireNonNull(schemaPointer, "schemaPointer");
        }
    }

    public record ValidationResult(List<Violation> violations, boolean truncated) {
        public ValidationResult {
            violations = ContractChecks.list(violations, "violations", 0, 64);
            ContractChecks.require(!truncated || !violations.isEmpty(), "Truncated result requires violations");
        }

        public boolean valid() {
            return violations.isEmpty();
        }
    }

    /**
     * 编译失败和业务值不匹配分开表达；异常只包含安全的错误码和 Schema 位置。
     */
    public static final class InvalidSchemaException extends IllegalArgumentException {

        @Serial
        private static final long serialVersionUID = 1478231879817703041L;

        private final Violation violation;

        private InvalidSchemaException(Violation violation) {
            super(violation.code() + " at schema pointer " + violation.schemaPointer());
            this.violation = violation;
        }

        public Violation violation() {
            return violation;
        }
    }

    /**
     * Schema 和值均采用低于 StructuredValue 的运行深度／节点上限；调用方只能在绝对范围内配置。
     */
    public record Limits(int schemaDepth, int schemaNodes, long schemaBytes, int valueDepth, int valueNodes,
                         long valueBytes, int maxErrors) {
        public Limits {
            ContractChecks.range(schemaDepth, "schemaDepth", 1, StructuredValue.MAX_DEPTH - 1);
            ContractChecks.range(schemaNodes, "schemaNodes", 1, StructuredValue.MAX_NODES - 1);
            ContractChecks.range(schemaBytes, "schemaBytes", 1, 1_000_000);
            ContractChecks.range(valueDepth, "valueDepth", 1, StructuredValue.MAX_DEPTH - 1);
            ContractChecks.range(valueNodes, "valueNodes", 1, StructuredValue.MAX_NODES - 1);
            ContractChecks.range(valueBytes, "valueBytes", 1, 32L * 1024 * 1024);
            ContractChecks.range(maxErrors, "maxErrors", 1, 64);
        }

        public static Limits defaults() {
            return new Limits(16, 2048, 256 * 1024, 24, 8192, 32L * 1024 * 1024, 32);
        }
    }

    public ToolSchemaValidator() {
        this(Limits.defaults());
    }

    public ToolSchemaValidator(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * 编译前限制整份 Schema（包括 enum、const 和 default 中的数据），只在真实 Schema 位置解释关键字。
     * 根必须声明本项目 dialect；输入及配置显式声明 object，输出可省略 type 以组合通用约束。
     */
    public CompiledSchema compile(DefinitionRef definition, ObjectValue document, Purpose purpose) {
        Objects.requireNonNull(definition, "definition").requireType("schema");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(purpose, "purpose");
        var budget = new Budget(limits.schemaDepth(), limits.schemaNodes(), limits.schemaBytes());
        var violation = budget.inspect(document, "");
        if (violation != null) throw invalid(violation.code(), violation.instancePointer());
        if (!(document.fields().get("$schema") instanceof StringValue dialect) || !DIALECT.equals(dialect.value())) {
            throw invalid(Code.INVALID_SCHEMA, "/$schema");
        }
        Node root = compileNode(document, "", true);
        if (purpose != Purpose.OUTPUT && !"object".equals(root.type())) throw invalid(Code.INVALID_SCHEMA, "/type");
        return new CompiledSchema(definition, document, purpose, root, limits);
    }

    /**
     * 固定 Schema 引用与原文保留供后续发布校验；此类型未注册为持久化或 HTTP 编码。
     */
    public static final class CompiledSchema {
        private final DefinitionRef definition;
        private final ObjectValue document;
        private final Purpose purpose;
        private final Node root;
        private final Limits limits;

        private CompiledSchema(DefinitionRef definition, ObjectValue document, Purpose purpose, Node root, Limits limits) {
            this.definition = definition;
            this.document = document;
            this.purpose = purpose;
            this.root = root;
            this.limits = limits;
        }

        public DefinitionRef definition() {
            return definition;
        }

        public ObjectValue document() {
            return document;
        }

        public Purpose purpose() {
            return purpose;
        }

        /**
         * maxBytes 来自后续已收紧的执行策略；不允许超过此校验器配置上限。
         * 每次调用独立维护遍历和错误预算，编译计划可跨线程复用。
         */
        public ValidationResult validate(StructuredValue value, long maxBytes) {
            Objects.requireNonNull(value, "value");
            ContractChecks.range(maxBytes, "maxBytes", 1, limits.valueBytes());
            var budget = new Budget(limits.valueDepth(), limits.valueNodes(), maxBytes);
            var violation = budget.inspect(value, "");
            if (violation != null) return new ValidationResult(List.of(violation), false);
            var errors = new Errors(limits.maxErrors());
            validateNode(root, value, "", errors);
            return new ValidationResult(errors.values, errors.truncated);
        }
    }

    private record Node(String pointer, String type, Set<StructuredValue> enumeration, StructuredValue constant,
                        Map<String, Node> properties, Set<String> required, boolean additionalProperties,
                        Node items, Integer minItems, Integer maxItems, Integer minLength, Integer maxLength,
                        BigDecimal minimum, BigDecimal maximum, BigDecimal exclusiveMinimum,
                        BigDecimal exclusiveMaximum) {
    }

    private Node compileNode(StructuredValue value, String path, boolean root) {
        if (!(value instanceof ObjectValue object)) throw invalid(Code.INVALID_SCHEMA, path);
        var fields = object.fields();
        for (var keyword : new TreeSet<>(fields.keySet())) {
            if (!KEYWORDS.contains(keyword)) throw invalid(Code.UNSUPPORTED_KEYWORD, pointer(path, keyword));
        }
        if (!root && fields.containsKey("$schema")) throw invalid(Code.INVALID_SCHEMA, pointer(path, "$schema"));
        String type = null;
        if (fields.containsKey("type")) {
            type = string(fields.get("type"), pointer(path, "type"));
            if (!TYPES.contains(type)) throw invalid(Code.INVALID_SCHEMA, pointer(path, "type"));
        }
        for (var keyword : List.of("title", "description")) {
            if (fields.containsKey(keyword)) string(fields.get(keyword), pointer(path, keyword));
        }
        Set<StructuredValue> enumeration = null;
        if (fields.containsKey("enum")) {
            if (!(fields.get("enum") instanceof ArrayValue array) || array.values().isEmpty()) {
                throw invalid(Code.INVALID_SCHEMA, pointer(path, "enum"));
            }
            var unique = new HashSet<>(array.values());
            if (unique.size() != array.values().size()) throw invalid(Code.INVALID_SCHEMA, pointer(path, "enum"));
            enumeration = Set.copyOf(unique);
        }
        var properties = new TreeMap<String, Node>();
        if (fields.containsKey("properties")) {
            if (!(fields.get("properties") instanceof ObjectValue declared))
                throw invalid(Code.INVALID_SCHEMA, pointer(path, "properties"));
            for (var name : new TreeSet<>(declared.fields().keySet())) {
                properties.put(name, compileNode(declared.fields().get(name), pointer(pointer(path, "properties"), name), false));
            }
        }
        boolean additional = false;
        if (fields.containsKey("additionalProperties")) {
            if (!(fields.get("additionalProperties") instanceof BooleanValue allowed)) {
                throw invalid(Code.INVALID_SCHEMA, pointer(path, "additionalProperties"));
            }
            additional = allowed.value();
        }
        var required = new TreeSet<String>();
        if (fields.containsKey("required")) {
            if (!(fields.get("required") instanceof ArrayValue array))
                throw invalid(Code.INVALID_SCHEMA, pointer(path, "required"));
            for (int i = 0; i < array.values().size(); i++) {
                var name = string(array.values().get(i), pointer(pointer(path, "required"), Integer.toString(i)));
                if (!required.add(name) || !additional && !properties.containsKey(name)) {
                    throw invalid(Code.INVALID_SCHEMA, pointer(path, "required"));
                }
            }
        }
        Node items = fields.containsKey("items") ? compileNode(fields.get("items"), pointer(path, "items"), false) : null;
        Integer minItems = count(fields, "minItems", path), maxItems = count(fields, "maxItems", path);
        Integer minLength = count(fields, "minLength", path), maxLength = count(fields, "maxLength", path);
        checkRange(minItems, maxItems, pointer(path, "maxItems"));
        checkRange(minLength, maxLength, pointer(path, "maxLength"));
        BigDecimal minimum = number(fields, "minimum", path), maximum = number(fields, "maximum", path);
        BigDecimal exclusiveMinimum = number(fields, "exclusiveMinimum", path), exclusiveMaximum = number(fields, "exclusiveMaximum", path);
        checkNumericRange(minimum, maximum, exclusiveMinimum, exclusiveMaximum, path);
        var node = new Node(path, type, enumeration, fields.get("const"), Collections.unmodifiableMap(properties),
                Collections.unmodifiableSet(required), additional, items, minItems, maxItems, minLength, maxLength,
                minimum, maximum, exclusiveMinimum, exclusiveMaximum);
        if (fields.containsKey("default")) {
            var errors = new Errors(1);
            var defaultValue = fields.get("default");
            var violation = new Budget(limits.valueDepth(), limits.valueNodes(), limits.valueBytes()).inspect(defaultValue, "");
            if (violation != null) throw invalid(violation.code(), pointer(path, "default"));
            validateNode(node, defaultValue, "", errors);
            if (!errors.values.isEmpty()) throw invalid(Code.INVALID_SCHEMA, pointer(path, "default"));
        }
        return node;
    }

    private static String string(StructuredValue value, String path) {
        if (!(value instanceof StringValue string)) throw invalid(Code.INVALID_SCHEMA, path);
        return string.value();
    }

    private static Integer count(Map<String, StructuredValue> fields, String name, String path) {
        if (!fields.containsKey(name)) return null;
        BigDecimal number = number(fields, name, path);
        try {
            int count = number.intValueExact();
            if (count < 0) throw invalid(Code.INVALID_SCHEMA, pointer(path, name));
            return count;
        } catch (ArithmeticException ignored) {
            throw invalid(Code.INVALID_SCHEMA, pointer(path, name));
        }
    }

    private static BigDecimal number(Map<String, StructuredValue> fields, String name, String path) {
        if (!fields.containsKey(name)) return null;
        if (!(fields.get(name) instanceof NumberValue number)) throw invalid(Code.INVALID_SCHEMA, pointer(path, name));
        return number.value();
    }

    private static void checkRange(Integer minimum, Integer maximum, String path) {
        if (minimum != null && maximum != null && minimum > maximum) throw invalid(Code.INVALID_SCHEMA, path);
    }

    private static void checkNumericRange(BigDecimal min, BigDecimal max, BigDecimal exclusiveMin, BigDecimal exclusiveMax, String path) {
        BigDecimal lower = min == null ? exclusiveMin : exclusiveMin == null ? min : min.max(exclusiveMin);
        BigDecimal upper = max == null ? exclusiveMax : exclusiveMax == null ? max : max.min(exclusiveMax);
        if (lower != null && upper != null && (lower.compareTo(upper) > 0 || lower.compareTo(upper) == 0
                && (exclusiveMin != null && exclusiveMin.compareTo(lower) == 0 || exclusiveMax != null && exclusiveMax.compareTo(upper) == 0))) {
            throw invalid(Code.INVALID_SCHEMA, path);
        }
    }

    private static void validateNode(Node node, StructuredValue value, String path, Errors errors) {
        if (errors.truncated) return;
        if (node.type() != null && !matches(node.type(), value)) {
            errors.add(Code.TYPE, path, pointer(node.pointer(), "type"));
            return;
        }
        if (node.enumeration() != null && !node.enumeration().contains(value))
            errors.add(Code.ENUM, path, pointer(node.pointer(), "enum"));
        if (node.constant() != null && !node.constant().equals(value))
            errors.add(Code.CONST, path, pointer(node.pointer(), "const"));
        if (errors.truncated) return;
        switch (value) {
            case ObjectValue object -> {
                for (String name : node.required()) {
                    if (!object.fields().containsKey(name))
                        errors.add(Code.REQUIRED, pointer(path, name), pointer(node.pointer(), "required"));
                    if (errors.truncated) return;
                }
                for (String name : new TreeSet<>(object.fields().keySet())) {
                    Node property = node.properties().get(name);
                    if (property != null)
                        validateNode(property, object.fields().get(name), pointer(path, name), errors);
                    else if (!node.additionalProperties())
                        errors.add(Code.ADDITIONAL_PROPERTY, pointer(path, name), pointer(node.pointer(), "additionalProperties"));
                    if (errors.truncated) return;
                }
            }
            case ArrayValue array -> {
                if (node.minItems() != null && array.values().size() < node.minItems())
                    errors.add(Code.MIN_ITEMS, path, pointer(node.pointer(), "minItems"));
                if (node.maxItems() != null && array.values().size() > node.maxItems())
                    errors.add(Code.MAX_ITEMS, path, pointer(node.pointer(), "maxItems"));
                if (node.items() != null) {
                    for (int i = 0; i < array.values().size() && !errors.truncated; i++)
                        validateNode(node.items(), array.values().get(i), pointer(path, Integer.toString(i)), errors);
                }
            }
            case StringValue string -> {
                int length = string.value().codePointCount(0, string.value().length());
                if (node.minLength() != null && length < node.minLength())
                    errors.add(Code.MIN_LENGTH, path, pointer(node.pointer(), "minLength"));
                if (node.maxLength() != null && length > node.maxLength())
                    errors.add(Code.MAX_LENGTH, path, pointer(node.pointer(), "maxLength"));
            }
            case NumberValue number -> {
                BigDecimal actual = number.value();
                if (node.minimum() != null && actual.compareTo(node.minimum()) < 0)
                    errors.add(Code.MINIMUM, path, pointer(node.pointer(), "minimum"));
                if (node.maximum() != null && actual.compareTo(node.maximum()) > 0)
                    errors.add(Code.MAXIMUM, path, pointer(node.pointer(), "maximum"));
                if (node.exclusiveMinimum() != null && actual.compareTo(node.exclusiveMinimum()) <= 0)
                    errors.add(Code.EXCLUSIVE_MINIMUM, path, pointer(node.pointer(), "exclusiveMinimum"));
                if (node.exclusiveMaximum() != null && actual.compareTo(node.exclusiveMaximum()) >= 0)
                    errors.add(Code.EXCLUSIVE_MAXIMUM, path, pointer(node.pointer(), "exclusiveMaximum"));
            }
            default -> {
            }
        }
    }

    private static boolean matches(String type, StructuredValue value) {
        return switch (type) {
            case "object" -> value instanceof ObjectValue;
            case "array" -> value instanceof ArrayValue;
            case "string" -> value instanceof StringValue;
            case "number" -> value instanceof NumberValue;
            case "integer" -> value instanceof NumberValue number && number.value().scale() <= 0;
            case "boolean" -> value instanceof BooleanValue;
            case "null" -> value == NullValue.INSTANCE;
            default -> throw new IllegalStateException("Uncompiled schema type");
        };
    }

    private static final class Errors {
        private final int maximum;
        private final List<Violation> values = new ArrayList<>();
        private boolean truncated;

        private Errors(int maximum) {
            this.maximum = maximum;
        }

        private void add(Code code, String path, String schemaPath) {
            if (values.size() == maximum) truncated = true;
            else values.add(new Violation(code, path, schemaPath));
        }
    }

    /**
     * 在匹配前遍历全部值，额外字段也不能绕过容量限制；不构造完整 JSON 字符串或字节数组。
     * bytes 为紧凑 JSON UTF-8 大小：必要控制字符转义、其他 Unicode 按 UTF-8 编码。
     */
    private static final class Budget {
        private final int maxDepth;
        private final int maxNodes;
        private final long maxBytes;
        private long bytes;
        private int nodes;

        private Budget(int maxDepth, int maxNodes, long maxBytes) {
            this.maxDepth = maxDepth;
            this.maxNodes = maxNodes;
            this.maxBytes = maxBytes;
        }

        private Violation inspect(StructuredValue value, String path) {
            return inspect(value, path, 1);
        }

        private Violation inspect(StructuredValue value, String path, int depth) {
            if (depth > maxDepth || ++nodes > maxNodes) return new Violation(Code.LIMIT_EXCEEDED, path, "");
            switch (value) {
                case ObjectValue object -> {
                    bytes += 2 + Math.max(0, object.fields().size() - 1);
                    for (String key : new TreeSet<>(object.fields().keySet())) {
                        if (!stringBytes(key)) return new Violation(Code.INVALID_UNICODE, pointer(path, key), "");
                        bytes++;
                        if (bytes > maxBytes) return new Violation(Code.LIMIT_EXCEEDED, pointer(path, key), "");
                        var violation = inspect(object.fields().get(key), pointer(path, key), depth + 1);
                        if (violation != null) return violation;
                    }
                }
                case ArrayValue array -> {
                    bytes += 2 + Math.max(0, array.values().size() - 1);
                    for (int i = 0; i < array.values().size(); i++) {
                        var violation = inspect(array.values().get(i), pointer(path, Integer.toString(i)), depth + 1);
                        if (violation != null) return violation;
                    }
                }
                case StringValue string -> {
                    if (!stringBytes(string.value())) return new Violation(Code.INVALID_UNICODE, path, "");
                }
                case NumberValue number -> bytes += number.value().toString().length();
                case BooleanValue bool -> bytes += bool.value() ? 4 : 5;
                case NullValue ignored -> bytes += 4;
            }
            return bytes > maxBytes ? new Violation(Code.LIMIT_EXCEEDED, path, "") : null;
        }

        private boolean stringBytes(String value) {
            bytes += 2;
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch == '"' || ch == '\\' || ch == '\b' || ch == '\t' || ch == '\n' || ch == '\f' || ch == '\r')
                    bytes += 2;
                else if (ch < 0x20) bytes += 6;
                else if (ch < 0x80) bytes++;
                else if (ch < 0x800) bytes += 2;
                else if (Character.isHighSurrogate(ch)) {
                    if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
                    bytes += 4;
                } else if (Character.isLowSurrogate(ch)) return false;
                else bytes += 3;
            }
            return true;
        }
    }

    private static String pointer(String parent, String token) {
        return parent + "/" + token.replace("~", "~0").replace("/", "~1");
    }

    private static InvalidSchemaException invalid(Code code, String path) {
        return new InvalidSchemaException(new Violation(code, "", path));
    }
}
