package com.arte.ai.service.tool;

import com.arte.ai.api.tool.Tool;
import com.arte.ai.api.tool.ToolResult;
import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;
import com.arte.ai.common.enums.tool.ToolRiskLevelEnum;
import com.arte.ai.common.exception.DuplicateToolException;
import com.arte.ai.pojo.tool.*;
import org.junit.Before;
import org.junit.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.Assert.*;

public class DefaultToolRegistryTest {

    private final Set<ToolReference> available = new HashSet<>();
    private DefaultToolRegistry registry;

    @Before
    public void setUp() {
        registry = new DefaultToolRegistry(
                new DefaultToolDefinitionValidator(new ObjectMapper()),
                available::contains
        );
    }

    @Test
    public void shouldExposeOnlyAvailableTools() {
        TestTool tool = new TestTool(definition());
        available.add(tool.getDefinition().reference());
        registry.register("local-java", tool);

        assertTrue(registry.resolve(tool.getDefinition().reference()).isPresent());
        assertEquals(1, registry.search(new ToolQuery("article", "summary",
                Set.of("article"), ToolRiskLevelEnum.MEDIUM, false)).size());

        available.clear();
        assertFalse(registry.resolve(tool.getDefinition().reference()).isPresent());
        assertTrue(registry.search(null).isEmpty());
    }

    @Test(expected = DuplicateToolException.class)
    public void shouldRejectSameIdentityAndVersion() {
        ToolDefinition definition = definition();
        available.add(definition.reference());
        registry.register("local-java", new TestTool(definition));
        registry.register("local-java", new TestTool(definition));
    }

    @Test(expected = IllegalStateException.class)
    public void shouldRejectUnpublishedTool() {
        registry.register("local-java", new TestTool(definition()));
    }

    @Test
    public void shouldUnregisterOnlySelectedProvider() {
        ToolDefinition summarize = definition("summarize");
        ToolDefinition translate = definition("translate");
        available.add(summarize.reference());
        available.add(translate.reference());
        registry.register("provider-a", new TestTool(summarize));
        registry.register("provider-b", new TestTool(translate));

        registry.unregisterProvider("provider-a");

        assertFalse(registry.resolve(summarize.reference()).isPresent());
        assertTrue(registry.resolve(translate.reference()).isPresent());
    }

    private ToolDefinition definition() {
        return definition("summarize");
    }

    private ToolDefinition definition(String name) {
        return new ToolDefinition(
                new ToolReference("article", name, "1.0.0"),
                "Article summary",
                "Summarize an article into concise structured content.",
                new ToolSchema("https://json-schema.org/draft/2020-12/schema",
                        "{\"type\":\"object\",\"properties\":{}}"),
                new ToolSchema("https://json-schema.org/draft/2020-12/schema",
                        "{\"type\":\"object\",\"properties\":{}}"),
                new ToolCapabilities(false, Set.of(ToolExecutionModeEnum.BLOCKING),
                        false, false, Set.of("text"), Set.of("structured")),
                new ToolRiskProfile(ToolRiskLevelEnum.LOW,
                        true, false, true, true, false, Set.of(), Set.of()),
                new ToolExecutionPolicy(ToolExecutionModeEnum.BLOCKING, Duration.ofSeconds(30),
                        0, Duration.ZERO, 2048, false, true),
                Set.of("article"),
                false
        );
    }

    private record TestTool(ToolDefinition definition)
            implements Tool<DynamicToolRequest, DynamicToolResponse> {

        @Override
        public ToolDefinition getDefinition() {
            return definition;
        }

        @Override
        public CompletionStage<ToolResult<DynamicToolResponse>> execute(
                ToolInvocation<DynamicToolRequest> invocation) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("not used by registry test"));
        }
    }
}
