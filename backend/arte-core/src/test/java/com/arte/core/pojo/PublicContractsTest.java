package com.arte.core.pojo;

import com.arte.core.pojo.reference.ArtifactRef;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.reference.ResourceVersionRef;
import com.arte.core.pojo.value.StructuredValue;
import com.arte.core.utils.platform.PlatformChecks;
import com.arte.core.utils.validation.ContractChecks;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PublicContractsTest {

    private static final String DIGEST = "a".repeat(64);

    @Test
    void referencesKeepFixedVersionsAndDraftDigestRequirements() {
        var definition = new DefinitionRef("schema", "document", "v1");
        assertSame(definition, definition.requireType("schema"));
        assertThrows(IllegalArgumentException.class, () -> definition.requireType("connection"));
        assertThrows(IllegalArgumentException.class, () -> new DefinitionRef("schema", "document", "LATEST"));
        assertDoesNotThrow(() -> new ResourceVersionRef("article", "document", "v1", null, null, null));
        assertDoesNotThrow(() -> new ResourceVersionRef("article", "document", null, "draft", "section", DIGEST));
        assertThrows(IllegalArgumentException.class, () -> new ResourceVersionRef("article", "document", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ResourceVersionRef("article", "document", "latest", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ResourceVersionRef("article", "document", "v1", "draft", null, null));
    }

    @Test
    void artifactsKeepMimeDigestVersionAndSizeChecks() {
        assertDoesNotThrow(() -> new ArtifactRef("export", "v1", "application/json", DIGEST, 0));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactRef("export", "latest", "application/json", DIGEST, 0));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactRef("export", "v1", "text", DIGEST, 0));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactRef("export", "v1", "text/plain", "A".repeat(64), 0));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactRef("export", "v1", "text/plain", DIGEST, -1));
    }

    @Test
    void structuredValuesDefensivelyCopyNestedCollections() {
        var children = new ArrayList<StructuredValue>(List.of(new StructuredValue.StringValue("原文😀")));
        var array = new StructuredValue.ArrayValue(children);
        var fields = new HashMap<String, StructuredValue>(Map.of("content", array));
        var value = new StructuredValue.ObjectValue(fields);
        children.clear();
        fields.clear();
        assertEquals(array, value.fields().get("content"));
        assertEquals("原文😀", ((StructuredValue.StringValue) array.values().getFirst()).value());
        assertThrows(UnsupportedOperationException.class, () -> value.fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> array.values().clear());
    }

    @Test
    void structuredValuesKeepDepthNodeCharacterAndCollectionLimits() {
        StructuredValue value = StructuredValue.NullValue.INSTANCE;
        for (int depth = 1; depth < StructuredValue.MAX_DEPTH; depth++) {
            value = new StructuredValue.ArrayValue(List.of(value));
        }
        var deepest = value;
        assertThrows(IllegalArgumentException.class, () -> new StructuredValue.ArrayValue(List.of(deepest)));
        var leaf = new StructuredValue.ArrayValue(Collections.nCopies(100, StructuredValue.NullValue.INSTANCE));
        assertThrows(IllegalArgumentException.class, () -> new StructuredValue.ArrayValue(Collections.nCopies(100, leaf)));
        var text = new StructuredValue.StringValue("x".repeat(600_000));
        assertThrows(IllegalArgumentException.class, () -> new StructuredValue.ArrayValue(List.of(text, text)));
        assertThrows(IllegalArgumentException.class, () -> new StructuredValue.ArrayValue(
                Collections.nCopies(257, StructuredValue.NullValue.INSTANCE)));
        assertThrows(IllegalArgumentException.class, () -> new StructuredValue.ObjectValue(Map.of(
                "k".repeat(256), new StructuredValue.StringValue("x".repeat(999_900)))));
    }

    @Test
    void numbersKeepExactDecimalNormalizationAndPrecisionBounds() {
        assertEquals(new BigDecimal("12345678901234567890.12345"),
                new StructuredValue.NumberValue(new BigDecimal("12345678901234567890.1234500")).value());
        assertThrows(IllegalArgumentException.class, () -> new StructuredValue.NumberValue(new BigDecimal("1E-129")));
        assertThrows(IllegalArgumentException.class, () -> new StructuredValue.NumberValue(new BigDecimal("1E129")));
        assertThrows(IllegalArgumentException.class, () -> new StructuredValue.NumberValue(new BigDecimal("9".repeat(129))));
    }

    @Test
    void contractIdentifiersRemainDistinctFromStricterPlatformIdentifiers() {
        assertEquals(" id ", ContractChecks.id(" id ", "id"));
        assertEquals("id\u0001", ContractChecks.id("id\u0001", "id"));
        assertThrows(IllegalArgumentException.class, () -> PlatformChecks.id(" id ", "id"));
        assertThrows(IllegalArgumentException.class, () -> ContractChecks.id(" ", "id"));
        assertThrows(IllegalArgumentException.class, () -> ContractChecks.id("x".repeat(257), "id"));
        assertNull(ContractChecks.optionalId(null, "optional"));
    }

    @Test
    void collectionAndTimeChecksKeepBoundaryAndFailureSemantics() {
        var values = new ArrayList<>(List.of("a", "b"));
        var copied = ContractChecks.list(values, "values", 2, 2);
        values.clear();
        assertEquals(List.of("a", "b"), copied);
        assertThrows(UnsupportedOperationException.class, () -> copied.clear());
        assertThrows(IllegalArgumentException.class, () -> ContractChecks.set(List.of("a", "a"), "values", 2));
        assertThrows(NullPointerException.class, () -> ContractChecks.list(null, "values", 0, 2));
        assertEquals(10, ContractChecks.range(10, "value", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> ContractChecks.range(11, "value", 0, 10));
        assertDoesNotThrow(() -> ContractChecks.ordered(Instant.EPOCH, Instant.EPOCH, "time"));
        assertThrows(IllegalArgumentException.class, () -> ContractChecks.ordered(Instant.EPOCH, Instant.EPOCH.minusSeconds(1), "time"));
    }
}
