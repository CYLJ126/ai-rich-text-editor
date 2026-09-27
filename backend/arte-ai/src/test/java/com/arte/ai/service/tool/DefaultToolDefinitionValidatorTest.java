package com.arte.ai.service.tool;

import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;
import com.arte.ai.common.enums.tool.ToolRiskLevelEnum;
import com.arte.ai.common.exception.ToolDefinitionValidationException;
import com.arte.ai.pojo.tool.*;
import org.junit.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertTrue;

public class DefaultToolDefinitionValidatorTest {

    private final DefaultToolDefinitionValidator validator =
            new DefaultToolDefinitionValidator(new ObjectMapper());

    @Test
    public void shouldAcceptValidDefinition() {
        validator.validate(definition(false, true, """
                {"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}
                """));
    }

    @Test
    public void shouldRejectUndeclaredRequiredParameter() {
        try {
            validator.validate(definition(false, true, """
                    {"type":"object","properties":{"text":{"type":"string"}},"required":["missing"]}
                    """));
        } catch (ToolDefinitionValidationException exception) {
            assertTrue(exception.getViolations().stream()
                    .anyMatch(message -> message.contains("not declared")));
            return;
        }
        throw new AssertionError("validation should fail");
    }

    @Test(expected = ToolDefinitionValidationException.class)
    public void shouldRequireApprovalForDestructiveTool() {
        validator.validate(definition(true, false, """
                {"type":"object","properties":{}}
                """));
    }

    private ToolDefinition definition(boolean destructive, boolean requiresApproval, String inputSchema) {
        return new ToolDefinition(
                new ToolReference("article", "summarize", "1.0.0"),
                "Article summary",
                "Summarize an article into concise structured content.",
                new ToolSchema("https://json-schema.org/draft/2020-12/schema", inputSchema),
                new ToolSchema("https://json-schema.org/draft/2020-12/schema",
                        "{\"type\":\"object\",\"properties\":{}}"),
                new ToolCapabilities(false, Set.of(ToolExecutionModeEnum.BLOCKING),
                        false, false, Set.of("text"), Set.of("structured")),
                new ToolRiskProfile(destructive ? ToolRiskLevelEnum.HIGH
                        : ToolRiskLevelEnum.LOW,
                        !destructive, destructive, !destructive, true, false, Set.of(), Set.of()),
                Map.of(),
                new ToolExecutionPolicy(ToolExecutionModeEnum.BLOCKING, Duration.ofSeconds(30),
                        0, Duration.ZERO, 2048, requiresApproval, true),
                Set.of("article"),
                false
        );
    }
}
