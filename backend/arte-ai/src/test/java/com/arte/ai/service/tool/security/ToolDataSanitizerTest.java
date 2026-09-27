package com.arte.ai.service.tool.security;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ToolDataSanitizerTest {

    private final ToolDataSanitizer sanitizer = new ToolDataSanitizer();

    @Test
    public void shouldRecursivelyRedactSecretsAndRemoveCredentialReferences() {
        Map<String, Object> sanitized = sanitizer.sanitize(Map.of(
                "title", "article",
                "apiKey", "plain-secret",
                "credentialReference", "credential-id",
                "nested", Map.of("authorization", "Bearer token", "safe", true),
                "items", List.of(Map.of("password", "123456"))));

        assertEquals("article", sanitized.get("title"));
        assertEquals("***", sanitized.get("apiKey"));
        assertFalse(sanitized.containsKey("credentialReference"));
        assertEquals("***", ((Map<?, ?>) sanitized.get("nested")).get("authorization"));
        assertEquals("***", ((Map<?, ?>) ((List<?>) sanitized.get("items")).getFirst()).get("password"));
    }
}
