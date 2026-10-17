package com.arte.app.platform.persistence;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.serialization.ResolvedBindingJson;
import com.arte.app.platform.ai.AiControlData;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用平台真实 mapper 验证数据库目录及管理响应的旧格式，不访问数据库或更改目录。
 */
class PlatformBindingJsonTest {
    private final PlatformJson platform = new PlatformJson();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void generationCatalogKeepsItsLegacyShapeAndRoundTrips() {
        var binding = remote();
        var catalog = new AiControlData.Catalog(List.of(binding.capability()), List.of(binding), List.of(), List.of(),
                List.of(), List.of(), new AiControlData.Preset(false, List.of(), List.of()));
        String encoded = platform.write(catalog);
        var body = json.readTree(encoded).path("bindings").get(0);
        assertTrue(body.has("connection"));
        assertEquals("test-model", body.path("remoteOperation").asString());
        assertFalse(body.has("target"));
        assertEquals(catalog, platform.read(encoded, AiControlData.Catalog.class));
    }

    @Test
    void typedRemoteReadStillWritesTheOldPlatformFormat() {
        var typed = JsonMapper.builder().addModule(ResolvedBindingJson.module(true)).build().writeValueAsString(remote());
        assertEquals(remote(), platform.read(typed, ResolvedBinding.class));
        assertFalse(json.readTree(platform.write(platform.read(typed, ResolvedBinding.class))).has("target"));
        assertThrows(RuntimeException.class, () -> platform.read(typed.replace("REMOTE", "java.lang.Runtime"), ResolvedBinding.class));
    }

    @Test
    void existingModelCatalogCannotSilentlyEncodeLocalToolTargets() {
        var local = new ResolvedBinding(new DefinitionRef("binding", "tool", "v1"), capability(CapabilityDescriptor.Kind.TOOL),
                new ExecutionTarget.Local(new DefinitionRef("tool-adapter", "counter", "v1")), null, null);
        assertThrows(RuntimeException.class, () -> platform.write(local));
    }

    private static ResolvedBinding remote() {
        return new ResolvedBinding(new DefinitionRef("binding", "model", "v1"), capability(CapabilityDescriptor.Kind.GENERATION),
                new DefinitionRef("connection", "model", "v1"), "test-model", 1024L, null);
    }

    private static CapabilityDescriptor capability(CapabilityDescriptor.Kind kind) {
        return new CapabilityDescriptor(new DefinitionRef("capability", "text", "v1"), kind,
                new DefinitionRef("schema", "input", "v1"), new DefinitionRef("schema", "output", "v1"), Set.of(),
                CapabilityDescriptor.SideEffect.READ_ONLY, CapabilityDescriptor.Availability.EXECUTABLE);
    }
}
