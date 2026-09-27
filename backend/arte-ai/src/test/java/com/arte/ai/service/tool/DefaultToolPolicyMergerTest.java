package com.arte.ai.service.tool;

import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;
import com.arte.ai.pojo.tool.ToolExecutionPolicy;
import com.arte.ai.pojo.tool.ToolPolicyOverride;
import org.junit.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

import static org.junit.Assert.*;

public class DefaultToolPolicyMergerTest {

    private final DefaultToolPolicyMerger merger = new DefaultToolPolicyMerger();

    @Test
    public void shouldDecodePersistedPolicyAndApplyStricterOverride() {
        ToolExecutionPolicy base = merger.decodePolicy(Map.of(
                "executionMode", "blocking",
                "timeoutMillis", 30000,
                "maxRetries", 3,
                "retryBackoffMillis", 1000,
                "maxOutputTokens", 4096,
                "requiresApproval", false,
                "allowsResultCache", true
        ));
        ToolExecutionPolicy effective = merger.tighten(base, new ToolPolicyOverride(
                null, Duration.ofSeconds(10), 1, Duration.ofSeconds(2),
                1024, true, false));

        assertEquals(Duration.ofSeconds(10), effective.timeout());
        assertEquals(1, effective.maxRetries());
        assertEquals(1024, effective.maxOutputTokens());
        assertTrue(effective.requiresApproval());
        assertFalse(effective.allowsResultCache());
    }

    @Test
    public void shouldDecodePolicySnapshotProducedByApplicationObjectMapper() {
        ToolExecutionPolicy expected = policy(false, true);
        Map<String, Object> snapshot = new ObjectMapper().convertValue(expected,
                new TypeReference<Map<String, Object>>() {
                });
        assertEquals(expected, merger.decodePolicy(snapshot));
    }

    @Test
    public void shouldRejectDisablingRequiredApproval() {
        ToolExecutionPolicy base = policy(true, false);
        ToolPolicyOverride override = new ToolPolicyOverride(null, null, null,
                null, null, false, null);
        assertThrows(IllegalArgumentException.class, () -> merger.tighten(base, override));
    }

    @Test
    public void shouldRejectEnablingServerDisabledCache() {
        ToolExecutionPolicy base = policy(false, false);
        ToolPolicyOverride override = new ToolPolicyOverride(null, null, null,
                null, null, null, true);
        assertThrows(IllegalArgumentException.class, () -> merger.tighten(base, override));
    }

    @Test
    public void shouldRejectLongerTimeoutAndMoreRetries() {
        ToolExecutionPolicy base = policy(false, true);
        assertThrows(IllegalArgumentException.class, () -> merger.tighten(base,
                new ToolPolicyOverride(null, Duration.ofSeconds(31), null,
                        null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> merger.tighten(base,
                new ToolPolicyOverride(null, null, 3,
                        null, null, null, null)));
    }

    private ToolExecutionPolicy policy(boolean approval, boolean cache) {
        return new ToolExecutionPolicy(ToolExecutionModeEnum.BLOCKING, Duration.ofSeconds(30),
                2, Duration.ofSeconds(1), 4096, approval, cache);
    }
}
