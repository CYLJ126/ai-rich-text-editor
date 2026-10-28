package com.arte.ainew.tool.schema;

import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator.*;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.value.StructuredValue;
import com.arte.core.pojo.value.StructuredValue.*;
import org.junit.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

import static com.arte.ainew.application.tool.schema.ToolSchemaValidator.Code.*;
import static com.arte.ainew.application.tool.schema.ToolSchemaValidator.Code.MAX_ITEMS;
import static com.arte.ainew.application.tool.schema.ToolSchemaValidator.Purpose.*;
import static org.junit.Assert.*;

/**
 * 覆盖文档子集、嵌套错误位置和资源边界；不依赖 Spring、目录、数据库或外部服务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17
 */
public class ToolSchemaValidatorTest {
    private static final DefinitionRef REF = new DefinitionRef("schema", "tool-contract", "v1");
    private final ToolSchemaValidator validator = new ToolSchemaValidator();

    @Test
    public void compiledPlansReuseOnlyMatchingReferenceDigestPurposeAndLimits() {
        var document = object("$schema", text(ToolSchemaValidator.DIALECT), "type", text("object"));
        var reference = new DefinitionRef("schema", "cache-" + UUID.randomUUID(), "v1");
        var original = validator.compile(reference, document, INPUT);
        assertSame(original, new ToolSchemaValidator().compile(reference, document, INPUT));
        assertNotSame(original, validator.compile(reference, document, OUTPUT));
        assertNotSame(original, validator.compile(new DefinitionRef("schema", reference.id(), "v2"), document, INPUT));
        var changed = new HashMap<>(document.fields());
        changed.put("properties", object("required", object("type", text("string"))));
        changed.put("required", array(text("required")));
        var updated = validator.compile(reference, new ObjectValue(changed), INPUT);
        assertNotSame(original, updated);
        assertFalse(updated.validate(object(), 128).valid());
        var limits = new Limits(16, 2048, 256 * 1024, 24, 8192, 100, 32);
        var smaller = new ToolSchemaValidator(limits).compile(reference, document, INPUT);
        assertNotSame(original, smaller);
        assertThrows(IllegalArgumentException.class, () -> smaller.validate(object(), 101));
        var array = object("$schema", text(ToolSchemaValidator.DIALECT), "type", text("array"));
        validator.compile(reference, array, OUTPUT);
        assertThrows(InvalidSchemaException.class, () -> validator.compile(reference, array, INPUT));
    }

    @Test
    public void cachedPlansAreEvictedAndInvalidSchemasCannotUseAWarmPlan() {
        var reference = new DefinitionRef("schema", "evict-" + UUID.randomUUID(), "v1");
        var document = object("$schema", text(ToolSchemaValidator.DIALECT), "type", text("object"));
        var original = validator.compile(reference, document, INPUT);
        for (int i = 0; i < 256; i++)
            validator.compile(new DefinitionRef("schema", reference.id(), "other-" + i), document, INPUT);
        assertNotSame(original, validator.compile(reference, document, INPUT));
        var invalid = object("$schema", text(ToolSchemaValidator.DIALECT), "$ref", text("https://invalid.example/schema"));
        assertThrows(InvalidSchemaException.class, () -> validator.compile(reference, invalid, INPUT));
        assertSame(validator.compile(reference, document, INPUT), validator.compile(reference, document, INPUT));
    }

    @Test
    public void concurrentCompilationPublishesOneImmutablePlan() throws Exception {
        var reference = new DefinitionRef("schema", "concurrent-" + UUID.randomUUID(), "v1");
        var document = object("$schema", text(ToolSchemaValidator.DIALECT), "type", text("object"));
        try (var pool = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<java.util.concurrent.Future<CompiledSchema>>();
            for (int i = 0; i < 32; i++) results.add(pool.submit(() -> validator.compile(reference, document, INPUT)));
            var first = results.getFirst().get();
            for (var result : results) assertSame(first, result.get());
        }
    }

    @Test
    public void largePlansAreEvictedBeforeTheEntryLimitIsReached() {
        var reference = new DefinitionRef("schema", "weighted-" + UUID.randomUUID(), "v1");
        var document = object("$schema", text(ToolSchemaValidator.DIALECT), "type", text("object"), "description", text("x".repeat(180_000)));
        var original = validator.compile(reference, document, INPUT);
        for (int i = 0; i < 100; i++)
            validator.compile(new DefinitionRef("schema", reference.id(), "large-" + i), document, INPUT);
        assertNotSame(original, validator.compile(reference, document, INPUT));
    }

    @Test
    public void nestedObjectsAndArraysUseTheSameRules() {
        var schema = compile(object("type", text("object"), "properties", object("rows", object(
                        "type", text("array"), "items", object("type", text("object"), "properties", object(
                                "amount", object("type", text("integer"), "minimum", number("1"))), "required", array(text("amount"))))),
                "required", array(text("rows"))), INPUT);
        assertTrue(schema.validate(object("rows", array(object("amount", number("2")))), 1024).valid());
        var bad = schema.validate(object("rows", array(object("amount", number("0")), object())), 1024);
        assertEquals(List.of(new Violation(MINIMUM, "/rows/0/amount", "/properties/rows/items/properties/amount/minimum"),
                new Violation(REQUIRED, "/rows/1/amount", "/properties/rows/items/required")), bad.violations());
    }

    @Test
    public void inputAndConfigurationRequireAnExplicitObjectRoot() {
        for (var purpose : List.of(INPUT, CONFIGURATION)) {
            for (var doc : List.of(object(), object("type", text("array")), object("type", text("null")))) {
                assertSchemaError(INVALID_SCHEMA, "/type", () -> compile(doc, purpose));
            }
            assertTrue(compile(object("type", text("object")), purpose).validate(object(), 10).valid());
        }
    }

    @Test
    public void outputSupportsEveryDeclaredJsonType() {
        var instances = Map.<String, StructuredValue>of("object", object(), "array", array(), "string", text("🙂"),
                "number", number("0.1"), "integer", number("2.0"), "boolean", new BooleanValue(true), "null", NullValue.INSTANCE);
        for (var entry : instances.entrySet()) {
            var schema = compile(object("type", text(entry.getKey())), OUTPUT);
            assertTrue(entry.getKey(), schema.validate(entry.getValue(), 1024).valid());
            StructuredValue wrong = entry.getKey().equals("string") ? number("1") : text("wrong");
            assertCode(TYPE, schema.validate(wrong, 1024));
        }
    }

    @Test
    public void mathematicalIntegersAndFiniteDecimalsDoNotUseDoubleRounding() {
        var integer = compile(object("type", text("integer")), OUTPUT);
        for (String value : List.of("1.0", "1E+8", "-0.000", "9007199254740993")) {
            assertTrue(integer.validate(number(value), 128).valid());
        }
        assertCode(TYPE, integer.validate(number("0.00000000000000000001"), 128));
        var exact = compile(object("type", text("number"), "minimum", number("9007199254740993.01"),
                "maximum", number("9007199254740993.01")), OUTPUT);
        assertTrue(exact.validate(number("9007199254740993.010"), 128).valid());
        assertCode(MINIMUM, exact.validate(number("9007199254740993.00"), 128));
        assertCode(MAXIMUM, exact.validate(number("9007199254740993.02"), 128));
        assertThrows(NumberFormatException.class, () -> number("NaN"));
        assertThrows(NumberFormatException.class, () -> number("Infinity"));
    }

    @Test
    public void inclusiveAndExclusiveNumericBoundariesRemainDistinct() {
        var inclusive = compile(object("minimum", number("1"), "maximum", number("2")), OUTPUT);
        assertTrue(inclusive.validate(number("1"), 32).valid());
        assertTrue(inclusive.validate(number("2"), 32).valid());
        var exclusive = compile(object("exclusiveMinimum", number("1"), "exclusiveMaximum", number("2")), OUTPUT);
        assertCode(EXCLUSIVE_MINIMUM, exclusive.validate(number("1"), 32));
        assertCode(EXCLUSIVE_MAXIMUM, exclusive.validate(number("2"), 32));
        assertTrue(exclusive.validate(number("1.000000000000001"), 32).valid());
        assertTrue(exclusive.validate(text("untyped numeric constraints only apply to numbers"), 128).valid());
    }

    @Test
    public void contradictoryNumericRangesAreRejectedBeforeUse() {
        for (var fields : List.of(object("minimum", number("3"), "maximum", number("2")),
                object("minimum", number("2"), "exclusiveMaximum", number("2")),
                object("exclusiveMinimum", number("2"), "maximum", number("2")),
                object("exclusiveMinimum", number("3"), "exclusiveMaximum", number("2")),
                object("minimum", number("1"), "maximum", number("3"), "exclusiveMinimum", number("3")))) {
            assertSchemaError(INVALID_SCHEMA, "", () -> compile(fields, OUTPUT));
        }
        assertTrue(compile(object("minimum", number("2"), "maximum", number("2"),
                "exclusiveMinimum", number("1"), "exclusiveMaximum", number("3")), OUTPUT).validate(number("2"), 10).valid());
    }

    @Test
    public void allObjectsAreClosedByDefaultIncludingUntypedNestedObjects() {
        var schema = compile(object("type", text("object"), "properties", object("nested", object())), INPUT);
        assertCode(ADDITIONAL_PROPERTY, schema.validate(object("unknown", number("1")), 128));
        assertEquals("/nested/extra", schema.validate(object("nested", object("extra", number("1"))), 128)
                .violations().getFirst().instancePointer());
    }

    @Test
    public void explicitlyAllowedExtraPropertiesStillValidateKnownProperties() {
        var schema = compile(object("type", text("object"), "additionalProperties", new BooleanValue(true),
                "properties", object("known", object("type", text("boolean")))), INPUT);
        assertTrue(schema.validate(object("known", new BooleanValue(false), "extra", array(text("data"))), 1024).valid());
        assertCode(TYPE, schema.validate(object("known", number("0"), "extra", number("0")), 1024));
    }

    @Test
    public void requiredMissingIsDifferentFromAnExplicitNull() {
        var schema = compile(object("type", text("object"), "properties", object("nullable", object("type", text("null")),
                "string", object("type", text("string"))), "required", array(text("nullable"))), INPUT);
        assertCode(REQUIRED, schema.validate(object(), 128));
        assertTrue(schema.validate(object("nullable", NullValue.INSTANCE), 128).valid());
        assertCode(TYPE, schema.validate(object("nullable", NullValue.INSTANCE, "string", NullValue.INSTANCE), 128));
    }

    @Test
    public void requiredNamesMustBeUniqueAndReachableInClosedObjects() {
        for (var required : List.of(array(text("x"), text("x")), array(text("missing")), array(number("1")))) {
            assertSchemaError(INVALID_SCHEMA, required.values().getFirst() instanceof NumberValue ? "/required/0" : "/required",
                    () -> compile(object("type", text("object"), "properties", object("x", object()), "required", required), INPUT));
        }
        var open = compile(object("type", text("object"), "additionalProperties", new BooleanValue(true),
                "required", array(text("external"))), INPUT);
        assertTrue(open.validate(object("external", number("1")), 128).valid());
    }

    @Test
    public void propertyNamesAndDataObjectsAreNeverMistakenForKeywords() {
        var schema = compile(object("type", text("object"), "properties", object(
                "$ref", object("type", text("string")), "format", object("type", text("string")),
                "literal", object("type", text("object"), "additionalProperties", new BooleanValue(true),
                        "const", object("oneOf", text("business data"))))), INPUT);
        assertTrue(schema.validate(object("$ref", text("not resolved"), "format", text("field"),
                "literal", object("oneOf", text("business data"))), 1024).valid());
    }

    @Test
    public void unsupportedKeywordsAreRejectedAtRootAndNestedSchemaLocations() {
        for (String keyword : List.of("$ref", "$id", "$defs", "oneOf", "anyOf", "allOf", "not", "pattern", "format",
                "if", "then", "else", "prefixItems", "contains", "uniqueItems", "multipleOf", "typo")) {
            assertSchemaError(UNSUPPORTED_KEYWORD, "/" + keyword, () -> compile(object(keyword, text("ignored?")), OUTPUT));
            assertSchemaError(UNSUPPORTED_KEYWORD, "/properties/value/" + keyword,
                    () -> compile(object("properties", object("value", object(keyword, text("ignored?")))), OUTPUT));
            assertSchemaError(UNSUPPORTED_KEYWORD, "/items/" + keyword,
                    () -> compile(object("items", object(keyword, text("ignored?"))), OUTPUT));
        }
    }

    @Test
    public void dialectIsFixedAndNeverResolvedFromANetworkLocation() {
        for (var dialect : List.of(text("https://json-schema.org/draft/2020-12/schema"),
                text("http://127.0.0.1:1/schema"), NullValue.INSTANCE)) {
            assertSchemaError(INVALID_SCHEMA, "/$schema", () -> validator.compile(REF, object("$schema", dialect), OUTPUT));
        }
        assertSchemaError(INVALID_SCHEMA, "/$schema", () -> validator.compile(REF, object(), OUTPUT));
        assertSchemaError(INVALID_SCHEMA, "/items/$schema", () -> compile(object("items", document(object())), OUTPUT));
    }

    @Test
    public void invalidKeywordShapesAreNotCoercedOrIgnored() {
        var examples = Map.<String, StructuredValue>ofEntries(
                Map.entry("type", array(text("string"), text("null"))), Map.entry("enum", text("a")),
                Map.entry("properties", array()), Map.entry("required", text("x")),
                Map.entry("additionalProperties", object()), Map.entry("items", array(object())),
                Map.entry("minLength", number("-1")), Map.entry("minItems", number("0.5")),
                Map.entry("maxLength", number("2147483648")), Map.entry("maximum", text("2")),
                Map.entry("exclusiveMinimum", new BooleanValue(true)), Map.entry("title", number("1")),
                Map.entry("description", NullValue.INSTANCE));
        for (var example : examples.entrySet()) {
            assertSchemaError(INVALID_SCHEMA, "/" + example.getKey(), () -> compile(object(example.getKey(), example.getValue()), OUTPUT));
        }
        assertSchemaError(INVALID_SCHEMA, "/type", () -> compile(object("type", text("unknown")), OUTPUT));
        assertSchemaError(INVALID_SCHEMA, "/properties/field", () -> compile(object("properties", object("field", new BooleanValue(true))), OUTPUT));
        assertSchemaError(INVALID_SCHEMA, "/items", () -> compile(object("items", new BooleanValue(false)), OUTPUT));
    }

    @Test
    public void enumAndConstUseStructuralEqualityAndExactNormalizedNumbers() {
        var schema = compile(object("enum", array(number("1.0"), NullValue.INSTANCE, array(number("2.0")))), OUTPUT);
        assertTrue(schema.validate(number("1"), 128).valid());
        assertTrue(schema.validate(NullValue.INSTANCE, 128).valid());
        assertTrue(schema.validate(array(number("2")), 128).valid());
        assertCode(ENUM, schema.validate(text("1"), 128));
        var constant = compile(object("const", object("a", number("1"), "b", text("same")),
                "additionalProperties", new BooleanValue(true)), OUTPUT);
        assertTrue(constant.validate(object("b", text("same"), "a", number("1.00")), 128).valid());
        assertCode(CONST, constant.validate(object("a", number("2"), "b", text("same")), 128));
        var nullConst = compile(object("const", NullValue.INSTANCE), OUTPUT);
        assertTrue(nullConst.validate(NullValue.INSTANCE, 10).valid());
        assertCode(CONST, nullConst.validate(number("0"), 10));
    }

    @Test
    public void enumMustBeNonemptyAndHaveNoSemanticallyDuplicateMembers() {
        for (var enumeration : List.of(array(), array(number("1"), number("1.00")),
                array(object("a", number("1")), object("a", number("1.0"))))) {
            assertSchemaError(INVALID_SCHEMA, "/enum", () -> compile(object("enum", enumeration), OUTPUT));
        }
    }

    @Test
    public void stringLengthCountsUnicodeCodePointsWithoutNormalizingContent() {
        var schema = compile(object("type", text("string"), "minLength", number("1"), "maxLength", number("1")), OUTPUT);
        assertTrue(schema.validate(text("🙂"), 16).valid());
        assertTrue(schema.validate(text("中"), 16).valid());
        assertCode(MIN_LENGTH, schema.validate(text(""), 16));
        assertCode(MAX_LENGTH, schema.validate(text("e\u0301"), 16));
        assertCode(MAX_LENGTH, schema.validate(text("🙂🙂"), 16));
    }

    @Test
    public void arrayCountsAndItemsAreValidatedIndependently() {
        var schema = compile(object("type", text("array"), "minItems", number("1"), "maxItems", number("2"),
                "items", object("type", text("integer"))), OUTPUT);
        assertCode(MIN_ITEMS, schema.validate(array(), 128));
        assertTrue(schema.validate(array(number("1"), number("2")), 128).valid());
        assertCode(MAX_ITEMS, schema.validate(array(number("1"), number("2"), number("3")), 128));
        assertEquals("/1", schema.validate(array(number("1"), text("bad")), 128).violations().getFirst().instancePointer());
        assertTrue(compile(object("type", text("array")), OUTPUT).validate(array(text("any"), number("1")), 128).valid());
    }

    @Test
    public void contradictoryLengthAndArrayRangesFailDuringCompilation() {
        assertSchemaError(INVALID_SCHEMA, "/maxLength", () -> compile(object("minLength", number("2"), "maxLength", number("1")), OUTPUT));
        assertSchemaError(INVALID_SCHEMA, "/maxItems", () -> compile(object("minItems", number("2"), "maxItems", number("1")), OUTPUT));
    }

    @Test
    public void defaultsAreCheckedButNeverFillMissingExecutionInput() {
        var property = object("type", text("integer"), "minimum", number("1"), "default", number("2"));
        var schema = compile(object("type", text("object"), "properties", object("count", property),
                "required", array(text("count")), "title", text("Counter"), "description", text("Description")), INPUT);
        var input = object();
        assertCode(REQUIRED, schema.validate(input, 128));
        assertTrue(input.fields().isEmpty());
        assertSchemaError(INVALID_SCHEMA, "/default", () -> compile(object("type", text("integer"), "default", text("2")), OUTPUT));
        assertSchemaError(INVALID_SCHEMA, "/properties/n/default", () -> compile(object("properties", object("n",
                object("minimum", number("1"), "default", number("0")))), OUTPUT));
    }

    @Test
    public void rootDefaultMustMeetTheCompleteNestedContract() {
        var schema = object("type", text("object"), "properties", object("n", object("type", text("integer"))),
                "required", array(text("n")), "default", object());
        assertSchemaError(INVALID_SCHEMA, "/default", () -> compile(schema, INPUT));
    }

    @Test
    public void errorsUseEscapedStablePointersAndNeverContainActualValues() {
        var schema = compile(object("properties", object("a/b~c", object("type", text("string")))), OUTPUT);
        var result = schema.validate(object("a/b~c", number("123456789")), 128);
        assertEquals(List.of(new Violation(TYPE, "/a~1b~0c", "/properties/a~1b~0c/type")), result.violations());
        assertFalse(result.toString().contains("123456789"));
        assertThrows(UnsupportedOperationException.class, () -> result.violations().clear());
    }

    @Test
    public void compactJsonByteLimitsCountUtf8EscapesKeysAndPunctuation() {
        var open = compile(object("additionalProperties", new BooleanValue(true)), OUTPUT);
        // 对照现有 JSON 库的紧凑编码，不以 Java 字符数当作字节大小。
        var cases = List.<StructuredValue>of(object("中", text("🙂\n\u0000\"\\")), array(new BooleanValue(false), NullValue.INSTANCE),
                text("é"), number("1E+8"), text("\b\f\r\t"));
        var json = JsonMapper.builder().build();
        for (var value : cases) {
            int bytes = json.writeValueAsString(nativeValue(value)).getBytes(StandardCharsets.UTF_8).length;
            assertTrue(value.toString(), open.validate(value, bytes).valid());
            assertCode(LIMIT_EXCEEDED, open.validate(value, bytes - 1));
        }
    }

    @Test
    public void extraPropertiesCannotBypassByteDepthOrNodeLimits() {
        var small = new ToolSchemaValidator(new Limits(12, 128, 4096, 3, 5, 128, 4));
        var schema = small.compile(REF, document(object("type", text("object"), "additionalProperties", new BooleanValue(true))), INPUT);
        assertCode(LIMIT_EXCEEDED, schema.validate(object("extra", text("中".repeat(10))), 16));
        assertCode(LIMIT_EXCEEDED, schema.validate(object("extra", array(array(text("deep")))), 128));
        assertCode(LIMIT_EXCEEDED, schema.validate(object("extra", array(number("1"), number("2"), number("3"), number("4"))), 128));
        assertTrue(schema.validate(object("extra", array(number("1"), number("2"), number("3"))), 128).valid());
    }

    @Test
    public void schemaBudgetsIncludeMetadataAndLiteralDataNotJustSchemaNodes() {
        var depth = new ToolSchemaValidator(new Limits(3, 128, 4096, 8, 128, 4096, 4));
        assertSchemaError(LIMIT_EXCEEDED, "/default/a/0", () -> depth.compile(REF, document(object(
                "default", object("a", array(text("deep"))))), OUTPUT));
        var nodes = new ToolSchemaValidator(new Limits(12, 5, 4096, 8, 128, 4096, 4));
        assertSchemaError(LIMIT_EXCEEDED, "/enum/2", () -> nodes.compile(REF,
                document(object("enum", array(number("1"), number("2"), number("3")))), OUTPUT));
        var bytes = new ToolSchemaValidator(new Limits(12, 128, 128, 8, 128, 4096, 4));
        assertSchemaError(LIMIT_EXCEEDED, "/description", () -> bytes.compile(REF,
                document(object("description", text("中".repeat(100)))), OUTPUT));
    }

    @Test
    public void schemaBudgetBoundaryIsInclusiveAndAppliesToDialectBytes() {
        var doc = document(object("type", text("string")));
        long size = JsonMapper.builder().build().writeValueAsString(nativeValue(doc)).getBytes(StandardCharsets.UTF_8).length;
        var exact = new ToolSchemaValidator(new Limits(8, 128, size, 8, 128, 128, 4));
        assertTrue(exact.compile(REF, doc, OUTPUT).validate(text("ok"), 128).valid());
        var shortBudget = new ToolSchemaValidator(new Limits(8, 128, size - 1, 8, 128, 128, 4));
        assertSchemaError(LIMIT_EXCEEDED, "/type", () -> shortBudget.compile(REF, doc, OUTPUT));
    }

    @Test
    public void malformedUnicodeIsRejectedInValuesKeysAndSchemaData() {
        var schema = compile(object("additionalProperties", new BooleanValue(true)), OUTPUT);
        for (String malformed : List.of("\uD800", "\uDC00", "\uD800a")) {
            assertCode(INVALID_UNICODE, schema.validate(text(malformed), 128));
            assertCode(INVALID_UNICODE, schema.validate(object(malformed, text("x")), 128));
            assertSchemaError(INVALID_UNICODE, "/description", () -> compile(object("description", text(malformed)), OUTPUT));
        }
    }

    @Test
    public void errorCollectionIsBoundedDeterministicAndReportsTruncation() {
        var bounded = new ToolSchemaValidator(new Limits(12, 128, 4096, 8, 128, 4096, 2));
        var schema = bounded.compile(REF, document(object("type", text("object"))), INPUT);
        var input = object("z", number("1"), "a", number("1"), "m", number("1"));
        var result = schema.validate(input, 128);
        assertEquals(List.of("/a", "/m"), result.violations().stream().map(Violation::instancePointer).toList());
        assertTrue(result.truncated());
        assertFalse(schema.validate(object("m", number("1"), "a", number("1")), 128).truncated());
        assertEquals(result, schema.validate(input, 128));
    }

    @Test
    public void compiledPlansRetainFixedFactsAndAreSafeToReuseConcurrently() throws Exception {
        var doc = document(object("type", text("integer"), "minimum", number("1")));
        var schema = validator.compile(REF, doc, OUTPUT);
        assertEquals(REF, schema.definition());
        assertSame(doc, schema.document());
        assertEquals(OUTPUT, schema.purpose());
        assertThrows(UnsupportedOperationException.class, () -> schema.document().fields().clear());
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 100; i++) {
                final int input = i % 2;
                futures.add(executor.submit(() -> schema.validate(number(Integer.toString(input)), 128).valid() == (input == 1)));
            }
            for (var future : futures) assertTrue(future.get());
        }
    }

    @Test
    public void callerCannotBypassAbsoluteLimitsOrSchemaReferenceTypes() {
        assertThrows(IllegalArgumentException.class, () -> new Limits(32, 128, 4096, 8, 128, 4096, 4));
        assertThrows(IllegalArgumentException.class, () -> new Limits(8, 10_000, 4096, 8, 128, 4096, 4));
        assertThrows(IllegalArgumentException.class, () -> new Limits(8, 128, 1_000_001, 8, 128, 4096, 4));
        assertThrows(IllegalArgumentException.class, () -> new Limits(8, 128, 4096, 32, 128, 4096, 4));
        assertThrows(IllegalArgumentException.class, () -> new Limits(8, 128, 4096, 8, 10_000, 4096, 4));
        assertThrows(IllegalArgumentException.class, () -> new Limits(8, 128, 4096, 8, 128, 33L * 1024 * 1024, 4));
        assertThrows(IllegalArgumentException.class, () -> new Limits(8, 128, 4096, 8, 128, 4096, 65));
        assertThrows(IllegalArgumentException.class, () -> validator.compile(new DefinitionRef("tool", "a.b", "v1"), document(object()), OUTPUT));
        var schema = compile(object(), OUTPUT);
        assertThrows(IllegalArgumentException.class, () -> schema.validate(text("x"), 0));
        assertThrows(IllegalArgumentException.class, () -> schema.validate(text("x"), 32L * 1024 * 1024 + 1));
    }

    private CompiledSchema compile(ObjectValue fields, Purpose purpose) {
        return validator.compile(REF, document(fields), purpose);
    }

    private static ObjectValue document(ObjectValue fields) {
        var copy = new HashMap<>(fields.fields());
        copy.put("$schema", text(ToolSchemaValidator.DIALECT));
        return new ObjectValue(copy);
    }

    private static ObjectValue object(Object... pairs) {
        var fields = new HashMap<String, StructuredValue>();
        for (int i = 0; i < pairs.length; i += 2) fields.put((String) pairs[i], (StructuredValue) pairs[i + 1]);
        return new ObjectValue(fields);
    }

    private static StringValue text(String text) {
        return new StringValue(text);
    }

    private static NumberValue number(String text) {
        return new NumberValue(new BigDecimal(text));
    }

    private static ArrayValue array(StructuredValue... values) {
        return new ArrayValue(List.of(values));
    }

    private static void assertCode(Code expected, ValidationResult result) {
        assertFalse(result.valid());
        assertEquals(expected, result.violations().getFirst().code());
    }

    private static void assertSchemaError(Code code, String pointer, Runnable work) {
        var error = assertThrows(InvalidSchemaException.class, work::run);
        assertEquals(code, error.violation().code());
        assertEquals(pointer, error.violation().schemaPointer());
    }

    private static Object nativeValue(StructuredValue value) {
        return switch (value) {
            case ObjectValue object -> {
                var map = new TreeMap<String, Object>();
                object.fields().forEach((name, child) -> map.put(name, nativeValue(child)));
                yield map;
            }
            case ArrayValue array -> array.values().stream().map(ToolSchemaValidatorTest::nativeValue).toList();
            case StringValue string -> string.value();
            case NumberValue number -> number.value();
            case BooleanValue bool -> bool.value();
            case NullValue ignored -> null;
        };
    }
}
