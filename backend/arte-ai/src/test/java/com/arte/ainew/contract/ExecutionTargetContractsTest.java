package com.arte.ainew.contract;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.tool.ToolInvocation;
import org.junit.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 校验目标事实、旧构造器兼容和调用信封边界，不装配工具执行器。
 */
public class ExecutionTargetContractsTest {
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");
    private static final DefinitionRef BINDING = new DefinitionRef("binding", "text", "v1");
    private static final DefinitionRef CONNECTION = new DefinitionRef("connection", "model", "v1");
    private static final DefinitionRef ADAPTER = new DefinitionRef("tool-adapter", "text-counter", "v1");

    @Test
    public void localToolsNeedNeitherConnectionsNorModelCapacityOrRates() {
        var binding = local(BINDING, tool(CapabilityDescriptor.Availability.EXECUTABLE));
        assertEquals(new ExecutionTarget.Local(ADAPTER), binding.target());
        assertNull(binding.contextWindowTokens());
        assertNull(binding.rate());
        assertThrows(IllegalStateException.class, binding::connection);
        assertThrows(IllegalStateException.class, binding::remoteOperation);
        assertThrows(IllegalStateException.class, binding::requireRemoteTarget);
    }

    @Test
    public void legacyRemoteConstructorHasTheSameSingleTargetAsTheTypedConstructor() {
        var capability = generation();
        var legacy = new ResolvedBinding(BINDING, capability, CONNECTION, "model-name", 1024L, null);
        var typed = new ResolvedBinding(BINDING, capability, new ExecutionTarget.Remote(CONNECTION, "model-name"), 1024L, null);
        assertEquals(legacy, typed);
        assertEquals(CONNECTION, typed.connection());
        assertEquals("model-name", typed.remoteOperation());
    }

    @Test
    public void targetsRequireTheCorrectFixedReferenceKinds() {
        for (String type : Set.of("connection", "tool", "capability", "binding")) {
            assertThrows(IllegalArgumentException.class, () -> new ExecutionTarget.Local(new DefinitionRef(type, "id", "v1")));
        }
        assertThrows(IllegalArgumentException.class, () -> new ExecutionTarget.Remote(ADAPTER, "operation"));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionTarget.Remote(CONNECTION, " "));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionTarget.Remote(CONNECTION, "x".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> new DefinitionRef("tool-adapter", "id", "latest"));
        assertThrows(NullPointerException.class, () -> new ExecutionTarget.Local(null));
        assertThrows(NullPointerException.class, () -> new ExecutionTarget.Remote(null, "operation"));
    }

    @Test
    public void localTargetsCannotBeUsedAsModelOrOtherRemoteCapabilities() {
        for (var kind : CapabilityDescriptor.Kind.values()) {
            if (kind == CapabilityDescriptor.Kind.TOOL) continue;
            var capability = capability(kind, CapabilityDescriptor.Availability.EXECUTABLE);
            assertThrows(IllegalArgumentException.class, () -> local(BINDING, capability));
        }
        assertThrows(IllegalArgumentException.class, () -> new ResolvedBinding(BINDING,
                tool(CapabilityDescriptor.Availability.EXECUTABLE), new ExecutionTarget.Local(ADAPTER), 1024L, null));
        assertThrows(NullPointerException.class, () -> new ResolvedBinding(BINDING, generation(), null, 1024L, null));
    }

    @Test
    public void generationStillRequiresCapacityAndFixedRateTypes() {
        var target = new ExecutionTarget.Remote(CONNECTION, "model-name");
        assertThrows(IllegalArgumentException.class, () -> new ResolvedBinding(BINDING, generation(), target, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedBinding(BINDING, generation(), target, 0L, null));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedBinding(BINDING, generation(), target, 10_000_001L, null));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedBinding(BINDING, generation(), target, 1024L, CONNECTION));
    }

    @Test
    public void remoteToolsRemainIndependentOfModelWindows() {
        var binding = new ResolvedBinding(BINDING, tool(CapabilityDescriptor.Availability.EXECUTABLE),
                new ExecutionTarget.Remote(CONNECTION, "lookup"), null, null);
        assertEquals("lookup", binding.requireRemoteTarget().operation());
        assertNull(binding.contextWindowTokens());
    }

    @Test
    public void localGatewayCallsKeepDurableDispatchOwnerAndScopeChecks() {
        var binding = local(BINDING, tool(CapabilityDescriptor.Availability.EXECUTABLE));
        var context = context("subject", Set.of("ai:invoke"));
        var input = new ToolInvocation("call", new DefinitionRef("tool", "text.count", "v1"), new StructuredValue.ObjectValue(Map.of()));
        var request = request(input, context);
        var call = new GatewayCall<>(request, binding, attempt(Attempt.Dispatch.MAY_HAVE_EXECUTED, "operation"), ExecutionRuntimeContext.start(context));
        assertSame(binding.target(), call.binding().target());
        assertThrows(IllegalArgumentException.class, () -> new GatewayCall<>(request, binding,
                attempt(Attempt.Dispatch.NOT_STARTED, null), ExecutionRuntimeContext.start(context)));
        assertThrows(IllegalArgumentException.class, () -> new GatewayCall<>(request, binding,
                attempt(Attempt.Dispatch.MAY_HAVE_EXECUTED, "operation"), ExecutionRuntimeContext.start(context("other", Set.of("ai:invoke")))));
        assertThrows(IllegalArgumentException.class, () -> new GatewayCall<>(request, binding,
                attempt(Attempt.Dispatch.MAY_HAVE_EXECUTED, "operation"), ExecutionRuntimeContext.start(context("subject", Set.of("ai:invoke", "admin")))));
        assertThrows(IllegalArgumentException.class, () -> new GatewayCall<>(request,
                local(new DefinitionRef("binding", "text", "v2"), binding.capability()),
                attempt(Attempt.Dispatch.MAY_HAVE_EXECUTED, "operation"), ExecutionRuntimeContext.start(context)));
    }

    @Test
    public void localTargetDoesNotTurnDiscoveredCapabilitiesOrArbitraryInputsIntoTools() {
        var context = context("subject", Set.of("ai:invoke"));
        var input = new ToolInvocation("call", new DefinitionRef("tool", "text.count", "v1"), new StructuredValue.ObjectValue(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new GatewayCall<>(request(input, context),
                local(BINDING, tool(CapabilityDescriptor.Availability.DISCOVERED)),
                attempt(Attempt.Dispatch.MAY_HAVE_EXECUTED, "operation"), ExecutionRuntimeContext.start(context)));
        CapabilityInput arbitrary = () -> CapabilityDescriptor.Kind.TOOL;
        assertThrows(IllegalArgumentException.class, () -> new GatewayCall<>(request(arbitrary, context),
                local(BINDING, tool(CapabilityDescriptor.Availability.EXECUTABLE)),
                attempt(Attempt.Dispatch.MAY_HAVE_EXECUTED, "operation"), ExecutionRuntimeContext.start(context)));
    }

    private static ResolvedBinding local(DefinitionRef ref, CapabilityDescriptor capability) {
        return new ResolvedBinding(ref, capability, new ExecutionTarget.Local(ADAPTER), null, null);
    }

    private static CapabilityDescriptor generation() {
        return capability(CapabilityDescriptor.Kind.GENERATION, CapabilityDescriptor.Availability.EXECUTABLE);
    }

    private static CapabilityDescriptor tool(CapabilityDescriptor.Availability availability) {
        return capability(CapabilityDescriptor.Kind.TOOL, availability);
    }

    private static CapabilityDescriptor capability(CapabilityDescriptor.Kind kind, CapabilityDescriptor.Availability availability) {
        return new CapabilityDescriptor(new DefinitionRef("capability", "text", "v1"), kind,
                new DefinitionRef("schema", "input", "v1"), new DefinitionRef("schema", "output", "v1"), Set.of(),
                CapabilityDescriptor.SideEffect.READ_ONLY, availability);
    }

    private static ExecutionContext context(String subject, Set<String> scopes) {
        return new ExecutionContext("invocation", "trace", new ExecutionAuthorization(
                new ExecutionPrincipal(subject, "user", ExecutionPrincipal.Kind.USER), "tenant", "workspace", scopes, "grant"),
                NOW.plusSeconds(60), null, "budget", "release", "key");
    }

    private static <I extends CapabilityInput> InvocationRequest<I> request(I input, ExecutionContext context) {
        return new InvocationRequest<>(new DefinitionRef("capability", "text", "v1"), BINDING, input.kind(), input,
                new ExecutionOptions(NOW.plusSeconds(60), 1, 4096, 0, 0), context);
    }

    private static Attempt attempt(Attempt.Dispatch dispatch, String operation) {
        return new Attempt("attempt", "invocation", 1, "worker", 1, NOW.plusSeconds(30), 1,
                Attempt.State.RUNNING, dispatch, operation, null, Usage.unknown(), null, NOW, NOW);
    }
}
