package com.arte.ainew.persistence;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.serialization.ResolvedBindingJson;
import org.junit.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.Assert.*;

/**
 * 使用真实内部编码器及旧目录形状，验证版本、封闭目标和既有生成目录兼容。
 */
public class ResolvedBindingCodecTest {
    private static final DefinitionRef BINDING = new DefinitionRef("binding", "default", "v1");
    private static final DefinitionRef CONNECTION = new DefinitionRef("connection", "model", "v1");
    private final JacksonExecutionRecordCodec codec = new JacksonExecutionRecordCodec();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    public void legacyFlatBodyDecodesIntoTheSameRemoteTarget() {
        var mapper = JsonMapper.builder().addModule(ResolvedBindingJson.module(true)).build();
        assertEquals(remote(), mapper.readValue(json.writeValueAsString(legacyBody()), ResolvedBinding.class));
    }

    @Test
    public void explicitLegacyEnvelopeReadsAndReencodesAsVersionTwo() {
        var legacy = json.writeValueAsString(Map.of("schemaVersion", 1, "type", "resolved-binding", "body", legacyBody()));
        var binding = codec.decode(legacy, ResolvedBinding.class);
        assertEquals(remote(), binding);
        assertEquals(2, json.readTree(codec.encode(binding)).path("schemaVersion").asInt());
    }

    @Test
    public void remoteBindingRoundTripsWithAnExplicitDiscriminatorAndNoDuplicateFacts() {
        String encoded = codec.encode(remote());
        var tree = json.readTree(encoded);
        assertEquals(2, tree.path("schemaVersion").asInt());
        assertEquals("REMOTE", tree.path("body").path("target").path("kind").asString());
        assertFalse(tree.path("body").has("connection"));
        assertFalse(tree.path("body").has("remoteOperation"));
        assertEquals(remote(), codec.decode(encoded, ResolvedBinding.class));
    }

    @Test
    public void localBindingRoundTripsWithoutAnyConnectionOrRemoteOperation() {
        String encoded = codec.encode(local());
        assertEquals(local(), codec.decode(encoded, ResolvedBinding.class));
        assertEquals("LOCAL", json.readTree(encoded).path("body").path("target").path("kind").asString());
        assertFalse(encoded.contains("connection"));
        assertFalse(encoded.contains("remoteOperation"));
        assertFalse(encoded.contains("java.lang"));
    }

    @Test
    public void platformRemoteWriterKeepsTheExactLegacyJsonShape() {
        var mapper = JsonMapper.builder().addModule(ResolvedBindingJson.module(false)).build();
        String encoded = mapper.writeValueAsString(remote());
        assertEquals(json.readTree(json.writeValueAsString(legacyBody())), json.readTree(encoded));
        assertEquals(remote(), mapper.readValue(encoded, ResolvedBinding.class));
        // 平台目录仍只开放生成，不能把本地工具写成旧连接格式。
        assertThrows(RuntimeException.class, () -> mapper.writeValueAsString(local()));
    }

    @Test
    public void platformCompatibilityWorksWhenBindingsAreNestedInsideACatalog() {
        var mapper = JsonMapper.builder().addModule(ResolvedBindingJson.module(false)).build();
        var catalog = new BindingContainer(remote());
        var legacyCatalog = json.writeValueAsString(Map.of("binding", legacyBody()));
        assertEquals(catalog, mapper.readValue(legacyCatalog, BindingContainer.class));
        assertEquals(json.readTree(legacyCatalog), json.readTree(mapper.writeValueAsString(catalog)));
    }

    @Test
    public void unknownAliasesAndSnapshotVersionsFailClosed() {
        String encoded = codec.encode(remote());
        for (String version : Set.of("0", "3", "2.5", "\"2\"", "4294967298")) {
            assertThrows(RuntimeException.class, () -> codec.decode(encoded.replace("\"schemaVersion\":2", "\"schemaVersion\":" + version), ResolvedBinding.class));
        }
        assertThrows(IllegalArgumentException.class, () -> codec.decode(encoded.replace("resolved-binding", "java.lang.Runtime"), ResolvedBinding.class));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(encoded, com.arte.ainew.pojo.execution.Attempt.class));
    }

    @Test
    public void envelopeVersionAndTargetShapeCannotDisagree() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(codec.encode(remote()).replace("\"schemaVersion\":2", "\"schemaVersion\":1"), ResolvedBinding.class));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json.writeValueAsString(
                Map.of("schemaVersion", 2, "type", "resolved-binding", "body", legacyBody())), ResolvedBinding.class));
    }

    @Test
    public void nullOrNonObjectBindingBodiesNeverDecodeAsBindings() {
        for (int version : new int[]{1, 2}) {
            for (String body : Set.of("null", "[]", "false", "42", "\"binding\"")) {
                String encoded = "{\"schemaVersion\":" + version + ",\"type\":\"resolved-binding\",\"body\":" + body + "}";
                assertThrows(RuntimeException.class, () -> codec.decode(encoded, ResolvedBinding.class));
            }
        }
    }

    @Test
    public void mixedLegacyAndTypedFactsAreRejectedEvenWhenTheyAgreeOrAreNull() {
        var body = new TreeMap<String, Object>(legacyBody());
        body.put("target", Map.of("kind", "REMOTE", "connection", CONNECTION, "operation", "test-model"));
        rejectBody(body);
        body.put("connection", null);
        body.put("remoteOperation", null);
        rejectBody(body);
        body.remove("connection");
        body.remove("remoteOperation");
        body.put("typo", true);
        rejectBody(body);
    }

    @Test
    public void targetsCannotSelectClassesOrMixLocalAndRemoteDependencies() {
        for (var target : java.util.List.of(
                Map.of("kind", "java.lang.Runtime", "adapter", new DefinitionRef("tool-adapter", "counter", "v1")),
                Map.of("kind", "LOCAL", "adapter", new DefinitionRef("tool-adapter", "counter", "v1"), "connection", CONNECTION),
                Map.of("kind", "REMOTE", "connection", CONNECTION, "operation", "test-model", "adapter", "arbitrary"),
                Map.of("kind", "REMOTE", "connection", CONNECTION, "operation", true),
                Map.of("kind", "REMOTE", "connection", CONNECTION),
                Map.of("kind", "LOCAL", "adapter", CONNECTION))) {
            rejectTarget(target);
        }
    }

    @Test
    public void nullTargetNeverFallsBackToLegacyOrLocalExecution() {
        var body = typedBody();
        body.put("target", null);
        rejectBody(body);
        for (String field : Set.of("connection", "remoteOperation")) {
            var legacy = new TreeMap<>(legacyBody());
            legacy.remove(field);
            var mapper = JsonMapper.builder().addModule(ResolvedBindingJson.module(true)).build();
            assertThrows(RuntimeException.class, () -> mapper.readValue(json.writeValueAsString(legacy), ResolvedBinding.class));
        }
    }

    @Test
    public void constructorInvariantsAlsoApplyAfterDeserialization() {
        rejectTarget(Map.of("kind", "REMOTE", "connection", Map.of("type", "connection", "id", "model", "version", "latest"), "operation", "test-model"));
        // 不能把 GENERATION 绑定替换为本地目标，也不能通过数值转换绕过窗口校验。
        rejectTarget(Map.of("kind", "LOCAL", "adapter", new DefinitionRef("tool-adapter", "counter", "v1")));
        var body = typedBody();
        body.put("contextWindowTokens", 1024.5);
        rejectBody(body);
        body.put("contextWindowTokens", null);
        rejectBody(body);
    }

    private void rejectTarget(Map<?, ?> target) {
        var body = typedBody();
        body.put("target", target);
        rejectBody(body);
    }

    private void rejectBody(Object body) {
        assertThrows(RuntimeException.class, () -> codec.decode(json.writeValueAsString(
                Map.of("schemaVersion", 2, "type", "resolved-binding", "body", body)), ResolvedBinding.class));
    }

    private TreeMap<String, Object> typedBody() {
        var body = new TreeMap<>(legacyBody());
        body.remove("connection");
        body.remove("remoteOperation");
        body.put("target", Map.of("kind", "REMOTE", "connection", CONNECTION, "operation", "test-model"));
        return body;
    }

    private static Map<String, Object> legacyBody() {
        var result = new TreeMap<String, Object>();
        result.put("definition", BINDING);
        result.put("capability", capability(CapabilityDescriptor.Kind.GENERATION));
        result.put("connection", CONNECTION);
        result.put("remoteOperation", "test-model");
        result.put("contextWindowTokens", 1024L);
        result.put("rate", null);
        return result;
    }

    private static ResolvedBinding remote() {
        return new ResolvedBinding(BINDING, capability(CapabilityDescriptor.Kind.GENERATION), CONNECTION, "test-model", 1024L, null);
    }

    private static ResolvedBinding local() {
        return new ResolvedBinding(BINDING, capability(CapabilityDescriptor.Kind.TOOL),
                new ExecutionTarget.Local(new DefinitionRef("tool-adapter", "counter", "v1")), null, null);
    }

    private static CapabilityDescriptor capability(CapabilityDescriptor.Kind kind) {
        return new CapabilityDescriptor(new DefinitionRef("capability", "text", "v1"), kind,
                new DefinitionRef("schema", "input", "v1"), new DefinitionRef("schema", "output", "v1"), Set.of(),
                CapabilityDescriptor.SideEffect.READ_ONLY, CapabilityDescriptor.Availability.EXECUTABLE);
    }

    public record BindingContainer(ResolvedBinding binding) {
    }
}
