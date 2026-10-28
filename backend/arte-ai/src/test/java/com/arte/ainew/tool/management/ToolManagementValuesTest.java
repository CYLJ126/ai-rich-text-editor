package com.arte.ainew.tool.management;

import com.arte.ainew.web.ToolManagementValues;
import com.arte.ainew.web.request.ToolManagementRequests;
import com.arte.core.pojo.value.StructuredValue;
import org.junit.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.TreeMap;

import static org.junit.Assert.*;

/**
 * 精确 JSON、重复键、体积／深度和凭据字段边界，不依赖数据库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
public class ToolManagementValuesTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private ToolManagementRequests.Settings settings(String object) {
        return json.readValue("{\"tool\":{\"type\":\"tool\",\"id\":\"text.count\",\"version\":\"v1\"},\"versionPolicy\":\"PINNED\",\"name\":\"name\",\"enabled\":true,\"configuration\":" + object + "}", ToolManagementRequests.Settings.class);
    }

    @Test
    public void decimalAndLargeIntegerAreNeverRoundedThroughDouble() {
        var settings = ToolManagementValues.settings(settings("{\"n\":0.12345678901234567890123456789012345678,\"i\":9007199254740993123456789}"));
        var config = settings.configuration();
        assertEquals(new BigDecimal("0.12345678901234567890123456789012345678"), ((StructuredValue.NumberValue) config.fields().get("n")).value());
        assertEquals(new BigDecimal("9007199254740993123456789"), ((StructuredValue.NumberValue) config.fields().get("i")).value());
        var revision = new com.arte.ainew.pojo.tool.ToolBindingRevision(
                new com.arte.core.pojo.reference.DefinitionRef("binding", "exact-json", "r1"),
                new com.arte.ainew.common.execution.ExecutionOwner("tenant", "workspace", "subject"), settings,
                java.time.Instant.parse("2026-10-18T01:00:00Z"));
        var codec = new com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec();
        assertEquals(revision, codec.decode(codec.encode(revision), com.arte.ainew.pojo.tool.ToolBindingRevision.class));
    }

    @Test
    public void nullAndArraysRoundTripAsPlainJsonAndOutputMayBeNull() {
        var value = ToolManagementValues.settings(settings("{\"v\":null,\"a\":[1,true,\"x\"]}")).configuration();
        assertEquals("{\"a\":[1,true,\"x\"],\"v\":null}", json.writeValueAsString(ToolManagementValues.json(value)));
        var example = json.readValue("{\"input\":{},\"output\":null}", ToolManagementRequests.Example.class);
        assertTrue(example.output().isNull());
    }

    @Test
    public void repeatedConfigurationKeysAreRejected() {
        assertThrows(RuntimeException.class, () -> settings("{\"a\":1,\"a\":2}"));
    }

    @Test
    public void oversizedDepthWidthAndNumberScaleAreRejectedWhileReading() {
        String deep = "{\"a\":".repeat(30) + "0" + "}".repeat(30);
        assertThrows(RuntimeException.class, () -> settings(deep));
        var fields = new TreeMap<String, Object>();
        for (int n = 0; n < 257; n++) fields.put("v" + n, n);
        assertThrows(RuntimeException.class, () -> settings(json.writeValueAsString(fields)));
        assertThrows(RuntimeException.class, () -> settings("{\"n\":1e1000}"));
    }

    @Test
    public void arraysAndScalarsCannotReplaceTheRequiredConfigurationObject() {
        assertThrows(IllegalArgumentException.class, () -> ToolManagementValues.settings(settings("[]")));
        assertThrows(IllegalArgumentException.class, () -> ToolManagementValues.settings(settings("null")));
        assertThrows(IllegalArgumentException.class, () -> ToolManagementValues.settings(settings("123")));
    }

    @Test
    public void nestedCredentialsAreRejectedButOrdinaryTokenLimitsRemainUsable() {
        assertThrows(IllegalArgumentException.class, () -> ToolManagementValues.settings(settings("{\"nested\":[{\"API-KEY\":\"secret\"}]}")));
        assertNotNull(ToolManagementValues.settings(settings("{\"tokenLimit\":100}")));
    }

    @Test
    public void utf8CapacityDoesNotUseCharacterLength() {
        var value = settings("{\"text\":\"" + "中".repeat(400_000) + "\"}");
        assertThrows(IllegalArgumentException.class, () -> ToolManagementValues.settings(value));
    }

    @Test
    public void publicationExamplesMustFitTheirOwnByteLimitBeforeAnyWrite() {
        var request = json.readValue("{\"examples\":[{\"input\":{\"text\":\"" + "x".repeat(33_000) + "\"},\"output\":{}}],\"riskEvidence\":\"review\",\"minimumApproval\":\"NONE\"}", ToolManagementRequests.Review.class);
        assertThrows(IllegalArgumentException.class, () -> ToolManagementValues.review(request));
    }

    @Test
    public void revisionsRejectNonDecimalAndOutOfRangeValues() {
        assertEquals(Long.MAX_VALUE, ToolManagementValues.version(Long.toString(Long.MAX_VALUE)));
        for (var value : List.of("1.0", "-1", "01", "latest", "9223372036854775808"))
            assertThrows(IllegalArgumentException.class, () -> ToolManagementValues.version(value));
    }
}
