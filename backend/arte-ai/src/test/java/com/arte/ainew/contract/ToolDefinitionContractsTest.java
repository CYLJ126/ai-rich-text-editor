package com.arte.ainew.contract;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.tool.ToolCapabilities;
import com.arte.ainew.pojo.tool.ToolDefinition;
import com.arte.ainew.pojo.tool.ToolExecutionPolicy;
import com.arte.ainew.pojo.tool.ToolRiskProfile;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.*;

import static com.arte.ainew.pojo.control.CapabilityDescriptor.Feature.*;
import static com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect.*;
import static com.arte.ainew.pojo.tool.ToolExecutionPolicy.ApprovalMode.*;
import static org.junit.Assert.*;

/**
 * S1-01：验证定义身份、不可变快照和跨风险／策略约束，不启动应用、数据库或工具。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17
 */
public class ToolDefinitionContractsTest {
    private static final DefinitionRef TOOL = new DefinitionRef("tool", "text.countCodePoints", "v1");
    private static final DefinitionRef CAPABILITY = new DefinitionRef("capability", "text-counter", "v1");
    private static final DefinitionRef INPUT = new DefinitionRef("schema", "text-counter-input", "v1");
    private static final DefinitionRef OUTPUT = new DefinitionRef("schema", "text-counter-output", "v1");
    private static final DefinitionRef CONFIGURATION = new DefinitionRef("schema", "text-counter-config", "v1");
    private static final String CHECKSUM = "a".repeat(64);

    @Test
    public void capabilityProjectionKeepsOneSchemaAndRiskSourceWithoutPublishing() {
        var definition = definition(risk(READ_ONLY, false), policy(NONE));
        var capability = definition.discoveredCapability();
        assertEquals(CAPABILITY, capability.definition());
        assertEquals(CapabilityDescriptor.Kind.TOOL, capability.kind());
        assertEquals(INPUT, capability.inputSchema());
        assertEquals(OUTPUT, capability.outputSchema());
        assertEquals(definition.risk().sideEffect(), capability.sideEffect());
        assertEquals(definition.capabilities().features(), capability.features());
        assertEquals(CapabilityDescriptor.Availability.DISCOVERED, capability.availability());
    }

    @Test
    public void definitionsRejectWrongReferenceKinds() {
        assertThrows(IllegalArgumentException.class, () -> definition(
                new DefinitionRef("binding", TOOL.id(), "v1"), CAPABILITY, INPUT, OUTPUT, CONFIGURATION, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> definition(
                TOOL, TOOL, INPUT, OUTPUT, CONFIGURATION, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> definition(
                TOOL, CAPABILITY, TOOL, OUTPUT, CONFIGURATION, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> definition(
                TOOL, CAPABILITY, INPUT, TOOL, CONFIGURATION, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> definition(
                TOOL, CAPABILITY, INPUT, OUTPUT, TOOL, CHECKSUM));
    }

    @Test
    public void stableToolNamesAreSeparateFromDisplayAndProtocolNames() {
        for (String id : List.of("count", "text.", ".count", "text..count", "text.count value", "text.1count")) {
            assertThrows(IllegalArgumentException.class, () -> definition(
                    new DefinitionRef("tool", id, "v1"), CAPABILITY, INPUT, OUTPUT, CONFIGURATION, CHECKSUM));
        }
        assertEquals("article.read-v2", definition(new DefinitionRef("tool", "article.read-v2", "v1"),
                CAPABILITY, INPUT, OUTPUT, CONFIGURATION, CHECKSUM).definition().id());
    }

    @Test
    public void checksumsMustBeFixedSha256Declarations() {
        for (String digest : List.of("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "z".repeat(64))) {
            assertThrows(IllegalArgumentException.class, () -> definition(
                    TOOL, CAPABILITY, INPUT, OUTPUT, CONFIGURATION, digest));
        }
    }

    @Test
    public void callerMutationsCannotChangeNestedDefinitionFacts() {
        var fields = new HashMap<String, StructuredValue>();
        fields.put("language", new StructuredValue.StringValue("zh-CN"));
        var nested = new StructuredValue.ObjectValue(fields);
        var configuration = new StructuredValue.ObjectValue(Map.of("preferences", nested));
        var tags = new HashSet<>(Set.of("text"));
        var scopes = new HashSet<>(Set.of("article:read"));
        var origins = new HashSet<>(Set.of(URI.create("https://example.test")));
        var features = new HashSet<>(Set.of(CANCEL));
        var risk = new ToolRiskProfile(READ_ONLY, false, ToolRiskProfile.Idempotency.DOMAIN_OPERATION_KEY, scopes, origins);
        var capabilities = new ToolCapabilities(features, true);
        var definition = new ToolDefinition(TOOL, "local", "计数", "Count text code points", tags,
                CAPABILITY, INPUT, OUTPUT, CONFIGURATION, configuration, capabilities, risk, policy(NONE), CHECKSUM);

        fields.clear();
        tags.clear();
        scopes.clear();
        origins.clear();
        features.clear();
        assertEquals(new StructuredValue.StringValue("zh-CN"),
                ((StructuredValue.ObjectValue) definition.defaultConfiguration().fields().get("preferences")).fields().get("language"));
        assertEquals(Set.of("text"), definition.tags());
        assertEquals(Set.of("article:read"), definition.risk().requiredScopes());
        assertEquals(Set.of(URI.create("https://example.test")), definition.risk().allowedNetworkOrigins());
        assertEquals(Set.of(CANCEL), definition.capabilities().features());
        assertThrows(UnsupportedOperationException.class, () -> nested.fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> definition.defaultConfiguration().fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> definition.tags().clear());
        assertThrows(UnsupportedOperationException.class, () -> definition.risk().requiredScopes().clear());
        assertThrows(UnsupportedOperationException.class, () -> definition.risk().allowedNetworkOrigins().clear());
        assertThrows(UnsupportedOperationException.class, () -> definition.capabilities().features().clear());
    }

    @Test
    public void readOnlyDoesNotMeanNoNetworkEgress() {
        var profile = new ToolRiskProfile(READ_ONLY, false, ToolRiskProfile.Idempotency.UNVERIFIED,
                Set.of("external:read"), Set.of(URI.create("https://example.test:8443")));
        assertEquals(NONE, profile.minimumApprovalMode());
        assertEquals(1, profile.allowedNetworkOrigins().size());
        assertEquals(ToolRiskProfile.Idempotency.UNVERIFIED, profile.idempotency());
    }

    @Test
    public void destructiveReadOnlyIsContradictory() {
        assertThrows(IllegalArgumentException.class, () -> risk(READ_ONLY, true));
    }

    @Test
    public void writeAndExternalEffectsCannotDisableHumanControl() {
        for (var sideEffect : List.of(RESOURCE_WRITE, EXTERNAL_EFFECT)) {
            var profile = risk(sideEffect, false);
            assertThrows(IllegalArgumentException.class, () -> definition(profile, policy(NONE)));
            assertEquals(REQUESTER_CONFIRMATION, definition(profile, policy(REQUESTER_CONFIRMATION)).defaultPolicy().approvalMode());
        }
    }

    @Test
    public void destructiveAndUnknownRequireIndependentApproval() {
        for (var profile : List.of(risk(RESOURCE_WRITE, true), risk(EXTERNAL_EFFECT, true), risk(UNKNOWN, false))) {
            assertThrows(IllegalArgumentException.class, () -> definition(profile, policy(NONE)));
            assertThrows(IllegalArgumentException.class, () -> definition(profile, policy(REQUESTER_CONFIRMATION)));
            assertEquals(INDEPENDENT_APPROVAL, definition(profile, policy(INDEPENDENT_APPROVAL)).defaultPolicy().approvalMode());
        }
    }

    @Test
    public void strongerApprovalCanAlwaysTightenTheRiskMinimum() {
        assertEquals(INDEPENDENT_APPROVAL,
                definition(risk(READ_ONLY, false), policy(INDEPENDENT_APPROVAL)).defaultPolicy().approvalMode());
        assertTrue(INDEPENDENT_APPROVAL.satisfies(REQUESTER_CONFIRMATION));
        assertTrue(REQUESTER_CONFIRMATION.satisfies(NONE));
        assertFalse(REQUESTER_CONFIRMATION.satisfies(INDEPENDENT_APPROVAL));
        assertFalse(NONE.satisfies(REQUESTER_CONFIRMATION));
        assertThrows(NullPointerException.class, () -> NONE.satisfies(null));
    }

    @Test
    public void retryUpperBoundDoesNotInventIdempotency() {
        var policy = new ToolExecutionPolicy(Duration.ofSeconds(30), 3, Duration.ofSeconds(1),
                1024, 2048, REQUESTER_CONFIRMATION);
        var definition = definition(risk(RESOURCE_WRITE, false), policy);
        assertEquals(3, definition.defaultPolicy().maxAttempts());
        assertEquals(ToolRiskProfile.Idempotency.NONE, definition.risk().idempotency());
    }

    @Test
    public void toolFeaturesCannotAdvertiseModelOnlyCapabilities() {
        for (var feature : List.of(TEXT_INPUT, IMAGE_INPUT, TOOL_CALLS, STRUCTURED_OUTPUT)) {
            assertThrows(IllegalArgumentException.class, () -> new ToolCapabilities(Set.of(feature), false));
        }
    }

    @Test
    public void asynchronousProviderTasksMustBeQueryable() {
        assertThrows(IllegalArgumentException.class, () -> new ToolCapabilities(Set.of(ASYNC_TASK), false));
        var capabilities = new ToolCapabilities(Set.of(ASYNC_TASK, TASK_QUERY, CANCEL), false);
        assertTrue(capabilities.features().contains(TASK_QUERY));
        assertFalse(capabilities.outcomeQuerySupported());
    }

    @Test
    public void businessOutcomeQueryIsIndependentOfAsynchronousTaskQuery() {
        var capabilities = new ToolCapabilities(Set.of(), true);
        assertTrue(capabilities.outcomeQuerySupported());
        assertFalse(capabilities.features().contains(ASYNC_TASK));
        assertFalse(capabilities.features().contains(TASK_QUERY));
    }

    @Test
    public void policyRejectsUnboundedAttemptsAndByteLimits() {
        for (int attempts : new int[]{0, 11}) {
            assertThrows(IllegalArgumentException.class, () -> new ToolExecutionPolicy(
                    Duration.ofSeconds(30), attempts, Duration.ZERO, 1024, 2048, NONE));
        }
        for (long bytes : new long[]{0, ToolExecutionPolicy.MAX_INPUT_BYTES + 1}) {
            assertThrows(IllegalArgumentException.class, () -> new ToolExecutionPolicy(
                    Duration.ofSeconds(30), 1, Duration.ZERO, bytes, 2048, NONE));
        }
        for (long bytes : new long[]{0, ToolExecutionPolicy.MAX_OUTPUT_BYTES + 1}) {
            assertThrows(IllegalArgumentException.class, () -> new ToolExecutionPolicy(
                    Duration.ofSeconds(30), 1, Duration.ZERO, 1024, bytes, NONE));
        }
    }

    @Test
    public void timeoutAndBackoffCannotExtendTheAbsoluteContract() {
        for (var timeout : List.of(Duration.ZERO, Duration.ofMillis(999), Duration.ofHours(1).plusNanos(1))) {
            assertThrows(IllegalArgumentException.class, () -> new ToolExecutionPolicy(timeout, 1,
                    Duration.ZERO, 1024, 2048, NONE));
        }
        for (var backoff : List.of(Duration.ofNanos(-1), Duration.ofSeconds(31))) {
            assertThrows(IllegalArgumentException.class, () -> new ToolExecutionPolicy(Duration.ofSeconds(30),
                    2, backoff, 1024, 2048, NONE));
        }
        var maximum = new ToolExecutionPolicy(Duration.ofHours(1), 10, Duration.ofHours(1),
                ToolExecutionPolicy.MAX_INPUT_BYTES, ToolExecutionPolicy.MAX_OUTPUT_BYTES, NONE);
        assertEquals(Duration.ofHours(1), maximum.timeout());
    }

    @Test
    public void originDeclarationsCannotSmuggleCredentialsOrRequestData() {
        for (String origin : List.of("file:///tmp/tool", "https://user:secret@example.test", "https://example.test/path",
                "https://example.test?q=secret", "https://example.test#fragment", "https://example.test:0", "https://example.test:65536",
                "https://" + "a".repeat(2048) + ".test")) {
            assertThrows(IllegalArgumentException.class, () -> new ToolRiskProfile(READ_ONLY, false,
                    ToolRiskProfile.Idempotency.NONE, Set.of(), Set.of(URI.create(origin))));
        }
    }

    @Test
    public void immutableDefinitionCanRoundTripWithoutRuntimeResources() throws Exception {
        var definition = definition(risk(READ_ONLY, false), policy(NONE));
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(definition);
        }
        ToolDefinition decoded;
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            decoded = (ToolDefinition) input.readObject();
        }
        assertEquals(definition, decoded);
        assertEquals(definition.discoveredCapability(), decoded.discoveredCapability());
        assertThrows(UnsupportedOperationException.class, () -> decoded.tags().clear());
    }

    private static ToolDefinition definition(ToolRiskProfile risk, ToolExecutionPolicy policy) {
        return new ToolDefinition(TOOL, "local", "计数", "Count text code points", Set.of("text"),
                CAPABILITY, INPUT, OUTPUT, CONFIGURATION, new StructuredValue.ObjectValue(Map.of()),
                new ToolCapabilities(Set.of(), false), risk, policy, CHECKSUM);
    }

    private static ToolDefinition definition(DefinitionRef tool, DefinitionRef capability, DefinitionRef input,
                                             DefinitionRef output, DefinitionRef configuration, String checksum) {
        return new ToolDefinition(tool, "local", "计数", "Count text code points", Set.of("text"), capability,
                input, output, configuration, new StructuredValue.ObjectValue(Map.of()),
                new ToolCapabilities(Set.of(), false), risk(READ_ONLY, false), policy(NONE), checksum);
    }

    private static ToolRiskProfile risk(CapabilityDescriptor.SideEffect sideEffect, boolean destructive) {
        return new ToolRiskProfile(sideEffect, destructive, ToolRiskProfile.Idempotency.NONE, Set.of(), Set.of());
    }

    private static ToolExecutionPolicy policy(ToolExecutionPolicy.ApprovalMode approvalMode) {
        return new ToolExecutionPolicy(Duration.ofSeconds(30), 1, Duration.ZERO, 262144, 1048576, approvalMode);
    }
}
