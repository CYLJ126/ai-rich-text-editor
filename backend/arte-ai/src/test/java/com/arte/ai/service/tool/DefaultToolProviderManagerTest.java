package com.arte.ai.service.tool;

import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;
import com.arte.ai.common.enums.tool.ToolRiskLevelEnum;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.pojo.tool.po.ToolVersionPo;
import org.junit.Before;
import org.junit.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.*;

import static org.junit.Assert.*;

public class DefaultToolProviderManagerTest {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private ObjectMapper objectMapper;
    private DefaultToolProviderManager manager;

    @Before
    public void setUp() {
        objectMapper = new ObjectMapper();
        manager = new DefaultToolProviderManager(List.of(), null, null, null,
                null, null, null, null, objectMapper, null, null, null, Runnable::run, false, null);
    }

    @Test
    public void shouldCalculateSameChecksumForEquivalentMapAndSetOrders() {
        Map<String, Object> firstConfiguration = new LinkedHashMap<>();
        firstConfiguration.put("nested", linkedMap("beta", 2, "alpha", 1));
        firstConfiguration.put("regions", new LinkedHashSet<>(List.of("west", "east")));

        Map<String, Object> secondConfiguration = new LinkedHashMap<>();
        secondConfiguration.put("regions", new LinkedHashSet<>(List.of("east", "west")));
        secondConfiguration.put("nested", linkedMap("alpha", 1, "beta", 2));

        ToolDefinition first = definition(
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}",
                firstConfiguration);
        ToolDefinition second = definition(
                "{\"required\":[\"name\"],\"properties\":{\"name\":{\"type\":\"string\"}},\"type\":\"object\"}",
                secondConfiguration);

        String firstChecksum = manager.checksum(first);
        String secondChecksum = manager.checksum(second);

        assertEquals(firstChecksum, secondChecksum);
        assertEquals(64, firstChecksum.length());
    }

    @Test
    public void shouldMatchUnchangedPublishedSnapshotIgnoringUnorderedSetValues() throws Exception {
        ToolDefinition definition = definition(
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}",
                Map.of("locale", "zh-CN"));
        ToolVersionPo persisted = persistedVersion(definition);
        persisted.setCapabilities(linkedMap(
                "outputModes", List.of("text", "structured"),
                "inputModes", List.of("structured"),
                "supportsDryRun", false,
                "supportsCancellation", false,
                "executionModes", List.of("non-blocking", "blocking"),
                "supportsStreaming", false));

        assertTrue(manager.matchesPersistedDefinition(persisted, definition));
    }

    @Test
    public void shouldRejectLegacyChecksumMigrationWhenDefinitionChanged() throws Exception {
        ToolDefinition definition = definition(
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}",
                Map.of("locale", "zh-CN"));
        ToolVersionPo persisted = persistedVersion(definition);
        persisted.setDescription("A changed published description");

        assertFalse(manager.matchesPersistedDefinition(persisted, definition));
    }

    private ToolVersionPo persistedVersion(ToolDefinition definition) throws Exception {
        return new ToolVersionPo()
                .setVersion(definition.reference().version())
                .setTitle(definition.title())
                .setDescription(definition.description())
                .setInputSchema(objectMapper.readValue(definition.inputSchema().schema(), MAP_TYPE))
                .setOutputSchema(objectMapper.readValue(definition.outputSchema().schema(), MAP_TYPE))
                .setCapabilities(linkedMap(
                        "supportsStreaming", false,
                        "executionModes", List.of("blocking", "non-blocking"),
                        "supportsCancellation", false,
                        "supportsDryRun", false,
                        "inputModes", List.of("structured"),
                        "outputModes", List.of("structured", "text")))
                .setRiskProfile(linkedMap(
                        "level", "medium",
                        "readOnly", false,
                        "destructive", false,
                        "reversible", false,
                        "idempotent", false,
                        "openWorld", false,
                        "requiredScopes", List.of(),
                        "allowedNetworkTargets", List.of()))
                .setDefaultConfiguration(definition.defaultConfiguration())
                .setDefaultPolicy(linkedMap(
                        "executionMode", "blocking",
                        "timeout", "PT30S",
                        "maxRetries", 0,
                        "retryBackoff", "PT0S",
                        "maxOutputTokens", 4096,
                        "requiresApproval", false,
                        "allowsResultCache", false))
                .setTags(definition.tags());
    }

    private ToolDefinition definition(String inputSchema, Map<String, Object> configuration) {
        return new ToolDefinition(
                new ToolReference("local", "name-assessment", "1.0.0"),
                "name-assessment",
                "Assess level for a given name",
                new ToolSchema("https://json-schema.org/draft/2020-12/schema", inputSchema),
                new ToolSchema("https://json-schema.org/draft/2020-12/schema",
                        "{\"type\":\"object\",\"properties\":{\"value\":{},\"rawContent\":{\"type\":\"string\"}}}"),
                new ToolCapabilities(false,
                        Set.of(ToolExecutionModeEnum.BLOCKING, ToolExecutionModeEnum.NON_BLOCKING),
                        false, false, Set.of("structured"), Set.of("structured", "text")),
                new ToolRiskProfile(ToolRiskLevelEnum.MEDIUM,
                        false, false, false, false, false, Set.of(), Set.of()),
                configuration,
                new ToolExecutionPolicy(ToolExecutionModeEnum.BLOCKING, Duration.ofSeconds(30),
                        0, Duration.ZERO, 4096, false, false),
                Set.of("spring-ai"),
                false);
    }

    private Map<String, Object> linkedMap(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return result;
    }
}
