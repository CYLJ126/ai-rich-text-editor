package com.arte.ainew.tool.discovery;

import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.spi.adapter.BusinessToolAdapter;
import com.arte.ainew.spi.adapter.LocalToolDefinitionSource;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地工具测试
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:30
 */
public final class LocalToolFixtures {

    private LocalToolFixtures() {
    }

    public static DefinitionRef ref(String type, String id, String version) {
        return new DefinitionRef(type, id, version);
    }

    public static StructuredValue.ObjectValue object(Object... pairs) {
        var fields = new java.util.LinkedHashMap<String, StructuredValue>();
        for (int i = 0; i < pairs.length; i += 2) fields.put((String) pairs[i], (StructuredValue) pairs[i + 1]);
        return new StructuredValue.ObjectValue(fields);
    }

    public static StructuredValue.StringValue text(String value) {
        return new StructuredValue.StringValue(value);
    }

    public static ToolSchemaDefinition schema(DefinitionRef ref, StructuredValue.ObjectValue document) {
        return new ToolSchemaDefinition(ref, document, ToolCatalogDigests.schema(document));
    }

    public static Fixture fixture(String providerId, String version) {
        var provider = new ToolProviderDefinition(ref("tool-provider", providerId, "v1"), ToolProviderDefinition.Kind.LOCAL,
                ref("tool-provider-adapter", providerId + "-source", "v1"), null);
        var schema = schema(ref("schema", providerId + "-object", "v1"), object("$schema", text(ToolSchemaValidator.DIALECT),
                "type", text("object"), "properties", object("text", object("type", text("string")))));
        var tool = new ToolDefinition(ref("tool", providerId + ".count", version), providerId, "Count", "Count text", Set.of("text"),
                ref("capability", providerId + "-count", version), schema.definition(), schema.definition(), schema.definition(),
                object(), new ToolCapabilities(Set.of(), false), new ToolRiskProfile(CapabilityDescriptor.SideEffect.READ_ONLY,
                false, ToolRiskProfile.Idempotency.NONE, Set.of(), Set.of()),
                new ToolExecutionPolicy(Duration.ofSeconds(30), 1, Duration.ZERO, 1024, 1024, ToolExecutionPolicy.ApprovalMode.NONE), "0".repeat(64));
        var adapter = ref("tool-adapter", providerId + "-count", version);
        return new Fixture(provider, schema, new ToolDefinitionRevision(withTitle(tool, tool.title()), provider.definition(),
                new ExecutionTarget.Local(adapter)), adapter);
    }

    public static ToolDefinition withTitle(ToolDefinition tool, String title) {
        var unsigned = new ToolDefinition(tool.definition(), tool.providerId(), title, tool.description(), tool.tags(), tool.capability(),
                tool.inputSchema(), tool.outputSchema(), tool.configurationSchema(), tool.defaultConfiguration(), tool.capabilities(),
                tool.risk(), tool.defaultPolicy(), "0".repeat(64));
        return new ToolDefinition(unsigned.definition(), unsigned.providerId(), unsigned.title(), unsigned.description(), unsigned.tags(),
                unsigned.capability(), unsigned.inputSchema(), unsigned.outputSchema(), unsigned.configurationSchema(), unsigned.defaultConfiguration(),
                unsigned.capabilities(), unsigned.risk(), unsigned.defaultPolicy(), ToolCatalogDigests.tool(unsigned));
    }

    public static BusinessToolAdapter adapter(CapabilityDescriptor capability) {
        return new BusinessToolAdapter() {
            public CapabilityDescriptor capability() {
                return capability;
            }

            public Mono<ToolResult> invoke(GatewayCall<ToolInvocation> call) {
                throw new AssertionError("Discovery must never invoke a tool");
            }
        };
    }

    public record Fixture(ToolProviderDefinition provider, ToolSchemaDefinition schema, ToolDefinitionRevision revision,
                   DefinitionRef adapterRef) {
        public LocalToolDefinitionSource.Definitions definitions() {
            return new LocalToolDefinitionSource.Definitions(List.of(schema), List.of(revision));
        }

        public LocalToolRegistry.Registration registration(LocalToolDefinitionSource source) {
            return new LocalToolRegistry.Registration(provider, source, Map.of(adapterRef, adapter(revision.tool().discoveredCapability())));
        }

        public LocalToolRegistry.Registration registration() {
            return registration(() -> Mono.just(definitions()));
        }
    }
}
