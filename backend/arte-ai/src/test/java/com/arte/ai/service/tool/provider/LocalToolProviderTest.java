package com.arte.ai.service.tool.provider;

import com.arte.ai.api.tool.Tool;
import com.arte.ai.api.tool.ToolResult;
import com.arte.ai.api.tool.security.GuardrailDecision;
import com.arte.ai.common.annotation.ToolRelease;
import com.arte.ai.common.annotation.ToolRisk;
import com.arte.ai.common.enums.tool.GuardrailPhaseEnum;
import com.arte.ai.common.enums.tool.NeverToolCancellation;
import com.arte.ai.common.enums.tool.ToolRiskLevelEnum;
import com.arte.ai.mcp.server.ArteMcpTools;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.service.tool.DefaultToolDefinitionValidator;
import com.arte.ai.service.tool.security.RiskApprovalGuardrail;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.ResolvableType;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class LocalToolProviderTest {

    @Test
    public void shouldLoadRiskAnnotationUsingExposedToolNameAndPreserveExecution() {
        LocalToolProvider provider = provider(new AnnotatedTools());
        ToolDefinition definition = definition(provider, "query-record");

        assertTrue(definition.riskProfile().readOnly());
        assertEquals(ToolRiskLevelEnum.LOW, definition.riskProfile().level());
        assertTrue(definition.riskProfile().idempotent());
        assertTrue(definition.riskProfile().openWorld());
        assertEquals(Set.of("record:read"), definition.riskProfile().requiredScopes());
        assertEquals(Set.of("https://records.example"), definition.riskProfile().allowedNetworkTargets());
        assertFalse(definition.defaultPolicy().requiresApproval());
        new DefaultToolDefinitionValidator(new ObjectMapper()).validate(definition);
        assertTrue(approvalDecision(definition) instanceof GuardrailDecision.Allowed);

        @SuppressWarnings("unchecked")
        Tool<DynamicToolRequest, DynamicToolResponse> tool =
                (Tool<DynamicToolRequest, DynamicToolResponse>) provider.resolve(definition.reference()).orElseThrow();
        ToolResult<DynamicToolResponse> result = tool.execute(invocation(definition))
                .toCompletableFuture().join();
        assertTrue(result instanceof ToolResult.Succeeded<?>);
        assertEquals("hello", new ObjectMapper().convertValue(
                ((ToolResult.Succeeded<DynamicToolResponse>) result).output().value(), String.class));
    }

    @Test
    public void shouldUseMethodNameWhenToolNameIsOmitted() {
        ToolDefinition definition = definition(provider(new AnnotatedTools()), "defaultName");

        assertTrue(definition.riskProfile().readOnly());
        assertEquals(ToolRiskLevelEnum.MEDIUM, definition.riskProfile().level());
    }

    @Test
    public void shouldKeepConservativeDefaultsForUnannotatedMethodsAndCallbacks() {
        LocalToolProvider provider = provider(new AnnotatedTools(), new PlainCallback());
        for (String name : new String[]{"unannotated", "callback-only"}) {
            ToolDefinition definition = definition(provider, name);
            assertEquals(SpringAiToolAdapter.localRiskProfile(), definition.riskProfile());
            assertEquals(SpringAiToolAdapter.defaultExecutionPolicy(), definition.defaultPolicy());
            assertTrue(approvalDecision(definition) instanceof GuardrailDecision.ApprovalRequired);
        }
    }

    @Test
    public void shouldRequireApprovalForHighRiskEvenWhenReadOnly() {
        ToolDefinition definition = definition(provider(new AnnotatedTools()), "sensitive-query");

        assertTrue(definition.riskProfile().readOnly());
        assertEquals(ToolRiskLevelEnum.HIGH, definition.riskProfile().level());
        assertTrue(approvalDecision(definition) instanceof GuardrailDecision.ApprovalRequired);
    }

    @Test
    public void shouldCreateValidApprovalPoliciesForDestructiveAndCriticalTools() {
        LocalToolProvider provider = provider(new AnnotatedTools());
        for (String name : new String[]{"delete-record", "critical-query"}) {
            ToolDefinition definition = definition(provider, name);
            assertTrue(definition.defaultPolicy().requiresApproval());
            new DefaultToolDefinitionValidator(new ObjectMapper()).validate(definition);
            assertTrue(approvalDecision(definition) instanceof GuardrailDecision.ApprovalRequired);
        }
    }

    @Test
    public void shouldReadAnnotationFromProxiedToolBean() {
        ProxyFactory factory = new ProxyFactory(new AnnotatedTools());
        factory.setProxyTargetClass(true);
        ToolDefinition definition = definition(provider(factory.getProxy()), "query-record");

        assertTrue(definition.riskProfile().readOnly());
        assertEquals(ToolRiskLevelEnum.LOW, definition.riskProfile().level());
    }

    @Test
    public void shouldRejectContradictoryRiskDeclaration() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> provider(new ContradictoryTools()));

        assertTrue(failure.getMessage().contains("read-only tool cannot be destructive"));
    }

    @Test
    public void shouldLoadRandomNameExampleAsReadOnlyLowRisk() {
        ToolDefinition definition = definition(provider(new ArteMcpTools()), "random-name");

        assertTrue(definition.riskProfile().readOnly());
        assertEquals(ToolRiskLevelEnum.LOW, definition.riskProfile().level());
        assertFalse(definition.riskProfile().idempotent());
        assertTrue(approvalDecision(definition) instanceof GuardrailDecision.Allowed);
    }

    @Test
    public void shouldUseIndependentMethodVersionAndDeclareHistoricCompatibility() {
        LocalToolProvider provider = provider(new ReleasedTools());
        SpringAiToolAdapter tool = (SpringAiToolAdapter) provider.resolve(
                new ToolReference("local", "released", "3.2.1")).orElseThrow();
        assertEquals(Set.of("3.2.0"), tool.getCompatibleVersions());
        assertTrue(provider.resolve(new ToolReference("local", "released", "1.0.1")).isEmpty());
    }

    public static class ReleasedTools {
        @org.springframework.ai.tool.annotation.Tool(name = "released", description = "Independently versioned tool")
        @ToolRelease(version = "3.2.1", compatibleWith = {"3.2.0"})
        public String query(String text) {
            return text;
        }
    }

    private LocalToolProvider provider(Object... beans) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        for (int index = 0; index < beans.length; index++) {
            Object bean = beans[index];
            RootBeanDefinition definition = new RootBeanDefinition(bean.getClass());
            definition.setInstanceSupplier(() -> bean);
            beanFactory.registerBeanDefinition("toolBean" + index, definition);
        }
        LocalToolProvider provider = new LocalToolProvider(
                beanFactory.getBeanProvider(ResolvableType.forClass(Tool.class)),
                beanFactory.getBeanProvider(ToolCallback.class),
                new SpringAiToolObjectLocator(beanFactory), new ObjectMapper(), Runnable::run,
                "local", "1.0.1");
        provider.refresh().toCompletableFuture().join();
        return provider;
    }

    private ToolDefinition definition(LocalToolProvider provider, String name) {
        return provider.resolve(new ToolReference("local", name, "1.0.1"))
                .orElseThrow().getDefinition();
    }

    private GuardrailDecision approvalDecision(ToolDefinition definition) {
        return new RiskApprovalGuardrail().evaluate(new GuardrailContext(
                        GuardrailPhaseEnum.PRE_EXECUTION, definition, invocation(definition), null, Map.of()))
                .toCompletableFuture().join();
    }

    private ToolInvocation<DynamicToolRequest> invocation(ToolDefinition definition) {
        ToolExecutionContext context = new ToolExecutionContext("run", null, null, "trace", null,
                new ToolPrincipal("owner", "owner", Set.of(), Set.of()), Instant.now().plusSeconds(30),
                NeverToolCancellation.INSTANCE, null, null, Map.of());
        return new ToolInvocation<>("call", definition.reference(),
                new DynamicToolRequest(Map.of("text", "hello")), context, definition.defaultPolicy());
    }

    public static class AnnotatedTools {
        @org.springframework.ai.tool.annotation.Tool(name = "query-record", description = "Query a record by text")
        @ToolRisk(readOnly = true, level = ToolRiskLevelEnum.LOW, idempotent = true, openWorld = true,
                requiredScopes = {"record:read"}, allowedNetworkTargets = {"https://records.example"})
        public String query(String text) {
            return text;
        }

        @org.springframework.ai.tool.annotation.Tool(description = "Query using the default method name")
        @ToolRisk(readOnly = true)
        public String defaultName(String text) {
            return text;
        }

        @org.springframework.ai.tool.annotation.Tool(description = "Use the default risk declaration")
        public String unannotated(String text) {
            return text;
        }

        @org.springframework.ai.tool.annotation.Tool(name = "sensitive-query", description = "Query sensitive record data")
        @ToolRisk(readOnly = true, level = ToolRiskLevelEnum.HIGH)
        public String sensitiveQuery(String text) {
            return text;
        }

        @org.springframework.ai.tool.annotation.Tool(name = "delete-record", description = "Delete a record by text")
        @ToolRisk(destructive = true)
        public String deleteRecord(String text) {
            return text;
        }

        @org.springframework.ai.tool.annotation.Tool(name = "critical-query", description = "Query critically sensitive data")
        @ToolRisk(readOnly = true, level = ToolRiskLevelEnum.CRITICAL)
        public String criticalQuery(String text) {
            return text;
        }
    }

    public static class ContradictoryTools {
        @org.springframework.ai.tool.annotation.Tool(description = "An inconsistent risk declaration")
        @ToolRisk(readOnly = true, destructive = true)
        public String contradictory(String text) {
            return text;
        }
    }

    private static class PlainCallback implements ToolCallback {
        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return org.springframework.ai.tool.definition.ToolDefinition.builder().name("callback-only")
                    .description("A programmatically registered callback")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}").build();
        }

        @Override
        public String call(String toolInput) {
            return toolInput;
        }
    }
}
