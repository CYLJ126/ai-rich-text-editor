package com.arte.ai.service.tool.security;

import com.arte.ai.pojo.tool.DynamicToolRequest;
import org.junit.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class ToolArgumentDigestTest {

    private final ToolArgumentDigest digest = new ToolArgumentDigest(new ObjectMapper());

    @Test
    public void shouldBeStableAcrossMapOrderAndDetectArgumentChanges() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("title", "AI agents");
        first.put("options", Map.of("language", "zh", "tags", List.of("ai", "tool")));
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("options", Map.of("tags", List.of("ai", "tool"), "language", "zh"));
        reordered.put("title", "AI agents");

        String firstDigest = digest.digest(new DynamicToolRequest(first));
        String reorderedDigest = digest.digest(new DynamicToolRequest(reordered));
        String changedDigest = digest.digest(new DynamicToolRequest(
                Map.of("title", "Changed", "options", first.get("options"))));

        assertEquals(firstDigest, reorderedDigest);
        assertNotEquals(firstDigest, changedDigest);
    }
}
