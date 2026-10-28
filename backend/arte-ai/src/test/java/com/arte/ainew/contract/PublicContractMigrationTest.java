package com.arte.ainew.contract;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.reference.SourceRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.tool.ToolResult;
import com.arte.ainew.pojo.tool.ToolSchemaDefinition;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.core.pojo.reference.ArtifactRef;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.reference.ResourceVersionRef;
import com.arte.core.pojo.value.StructuredValue;
import com.arte.core.utils.ContentDigests;
import org.junit.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.Assert.*;

/**
 * 读取迁移前由旧实现生成的固定样本，验证持久化格式和摘要，不用新实现生成预期值。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/28 17:02 ✾
 */
public class PublicContractMigrationTest {

    private static final String DIGEST = "a".repeat(64);
    private final JacksonExecutionRecordCodec codec = new JacksonExecutionRecordCodec();

    @Test
    public void oldSchemaSnapshotReadsIntoCoreTypesWithoutChangingCanonicalBytes() throws IOException {
        var expected = new ToolSchemaDefinition(new DefinitionRef("schema", "migration", "v1"), document(), ToolCatalogDigests.schema(document()));
        assertSnapshot("schema.json", ToolSchemaDefinition.class, expected);
    }

    @Test
    public void oldToolResultKeepsResourceDraftArtifactAndEveryStructuredValueKind() throws IOException {
        var expected = new InvocationResult.Tool(new ToolResult("call", ToolResult.Outcome.SUCCEEDED, document(),
                List.of(new SourceRef("citation", new ResourceVersionRef("article", "resource", "v1", "draft-1", "section-1", DIGEST), DIGEST)),
                List.of(new ArtifactRef("artifact", "v1", "application/json", DIGEST, 1024)), ExecutionError.SideEffect.NONE, null));
        assertSnapshot("tool-result.json", InvocationResult.class, expected);
    }

    @Test
    public void aiAndCoreEncodingAndHashingMatchOldV1Vectors() throws IOException {
        var vectors = JsonMapper.builder().build().readTree(resource("vectors.json"));
        String input = vectors.path("input").asString();
        String canonical = vectors.path("canonical").asString();
        assertEquals(canonical, CanonicalJson.normalize(input));
        assertEquals(canonical, com.arte.core.serialize.CanonicalJson.normalize(input));
        assertEquals(vectors.path("digest").asString(), CanonicalJson.sha256(canonical));
        assertEquals(vectors.path("digest").asString(), ContentDigests.sha256(canonical));
        assertEquals(vectors.path("key").asString(), CanonicalJson.key("租户", "workspace😀", "a:b", ""));
        assertEquals(vectors.path("emptyKey").asString(), CanonicalJson.key());
        assertEquals(vectors.path("textDigest").asString(), CanonicalJson.sha256("中文😀"));
        assertEquals("{\"a\":1,\"b\":2}", CanonicalJson.encode(CanonicalJson.fields("b", 2, "a", 1)));
        assertEquals(ContentDigests.sha256("{\"a\":1,\"b\":2}"), CanonicalJson.digest(Map.of("b", 2, "a", 1)));
    }

    @Test
    public void aiCompatibilityEntryKeepsItsSceneLimitsAndIdentifierRules() {
        assertEquals(1_000_000, ContractChecks.MAX_TEXT_CHARS);
        assertEquals(256, ContractChecks.MAX_ITEMS);
        assertEquals(64, ContractChecks.MAX_PARTS);
        assertEquals(128, ContractChecks.MAX_TOOLS);
        assertEquals(" id ", ContractChecks.id(" id ", "id"));
        assertNull(ContractChecks.optionalId(null, "optional"));
        assertEquals("id must be non-blank and at most 256 characters",
                assertThrows(IllegalArgumentException.class, () -> ContractChecks.id("", "id")).getMessage());
    }

    @Test
    public void evidenceParsingKeepsAiSpecificByteDepthAndNullRules() {
        assertNull(CanonicalJson.structured(null));
        assertEquals(StructuredValue.NullValue.INSTANCE, CanonicalJson.structured("null"));
        assertEquals(new StructuredValue.NumberValue(new BigDecimal("12345678901234567890.12345")),
                CanonicalJson.structured("12345678901234567890.1234500"));
        assertEquals(32766, ((StructuredValue.StringValue) CanonicalJson.structured("\"" + "x".repeat(32766) + "\"")).value().length());
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.structured("\"" + "x".repeat(32767) + "\""));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.structured("\"" + "中".repeat(10923) + "\""));
        assertNotNull(CanonicalJson.structured("[".repeat(24) + "null" + "]".repeat(24)));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.structured("[".repeat(25) + "null" + "]".repeat(25)));
    }

    @Test
    public void movingClassesDoesNotPermitClassNamesAsSnapshotTypeAliases() throws IOException {
        String schema = resource("schema.json");
        for (String type : List.of("com.arte.ainew.common.value.StructuredValue$ObjectValue", StructuredValue.ObjectValue.class.getName())) {
            assertThrows(RuntimeException.class, () -> codec.decode(schema.replace("\"type\":\"object\"", "\"type\":\"" + type + "\""), ToolSchemaDefinition.class));
        }
    }

    private <T> void assertSnapshot(String file, Class<T> type, T expected) throws IOException {
        String oldBytes = resource(file).strip();
        T decoded = codec.decode(oldBytes, type);
        assertEquals(expected, decoded);
        String currentBytes = CanonicalJson.normalize(codec.encode(decoded));
        assertEquals(oldBytes, currentBytes);
        assertEquals(CanonicalJson.sha256(oldBytes), CanonicalJson.sha256(currentBytes));
    }

    private static StructuredValue.ObjectValue document() {
        return new StructuredValue.ObjectValue(Map.of("value", new StructuredValue.ArrayValue(List.of(
                new StructuredValue.StringValue("中文😀"), new StructuredValue.NumberValue(new BigDecimal("12345678901234567890123456789.1234500")),
                new StructuredValue.BooleanValue(true), StructuredValue.NullValue.INSTANCE,
                new StructuredValue.ObjectValue(Map.of("nested", new StructuredValue.StringValue("ok")))))));
    }

    private static String resource(String file) throws IOException {
        try (var stream = Objects.requireNonNull(PublicContractMigrationTest.class.getResourceAsStream("/public-contract-v1/" + file))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
