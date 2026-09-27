package com.arte.ai.service.tool;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;

public class DefaultToolConfigurationMergerTest {

    private final DefaultToolConfigurationMerger merger = new DefaultToolConfigurationMerger();

    @Test
    @SuppressWarnings("unchecked")
    public void shouldRecursivelyMergeConfigurationWithoutMutatingDefaults() {
        Map<String, Object> defaults = Map.of(
                "format", "markdown",
                "limits", Map.of("maxChars", 1000, "maxSections", 10));
        Map<String, Object> overrides = Map.of(
                "limits", Map.of("maxChars", 500),
                "language", "zh-CN");

        Map<String, Object> result = merger.merge(defaults, overrides);
        Map<String, Object> limits = (Map<String, Object>) result.get("limits");

        assertEquals("markdown", result.get("format"));
        assertEquals("zh-CN", result.get("language"));
        assertEquals(500, limits.get("maxChars"));
        assertEquals(10, limits.get("maxSections"));
        assertEquals(1000, ((Map<?, ?>) defaults.get("limits")).get("maxChars"));
    }
}
