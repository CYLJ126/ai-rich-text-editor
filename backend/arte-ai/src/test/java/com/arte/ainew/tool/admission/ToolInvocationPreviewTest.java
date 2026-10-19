package com.arte.ainew.tool.admission;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.tool.ToolAdmissionInput;
import com.arte.ainew.pojo.tool.ToolResult;
import com.arte.ainew.serialization.ToolAdmissionDigests;
import com.arte.ainew.web.response.InvocationResultResponse;
import com.arte.core.enums.ResultCodeEnum;
import org.junit.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.arte.ainew.tool.discovery.LocalToolFixtures.object;
import static com.arte.ainew.tool.discovery.LocalToolFixtures.text;
import static org.junit.Assert.*;

/**
 * 预览零写入、相对方案确认及重放；复用真实 H2／MyBatis 受理事务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 09:47 ✾
 */
public class ToolInvocationPreviewTest {
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Test
    public void publicResultKeepsExactNumbersInStandardJsonText() throws Exception {
        var output = object("integer", new StructuredValue.NumberValue(new BigDecimal("9007199254740993")),
                "decimal", new StructuredValue.NumberValue(new BigDecimal("0.1234567890123456789")));
        var result = new InvocationResult.Tool(new ToolResult("call",
                ToolResult.Outcome.SUCCEEDED, output, List.of(), List.of(),
                ExecutionError.SideEffect.NONE, null));
        var response = InvocationResultResponse.from("original", result);
        var json = JsonMapper.builder().build();
        var wire = json.readTree(json.writeValueAsString(response)).get("outputJson").asString();
        assertTrue(wire.contains("9007199254740993"));
        assertTrue(wire.contains("0.1234567890123456789"));
        assertEquals("TOOL", json.readTree(json.writeValueAsString(response)).get("kind").asString());
    }

    @Test
    public void previewHasNoWritesAndDigestDoesNotDependOnTemporaryIdentityOrClock() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var tables = List.of("arte_ai_lock", "arte_ai_tool_binding_resolution", "arte_ai_invocation", "arte_ai_outbox", "arte_ai_attempt", "arte_ai_reservation");
            var before = tables.stream().map(f::count).toList();
            var first = f.service.preview(f.input(), f.context("preview-a", "a")).block(WAIT);
            var second = f.service.preview(f.input(), f.context("preview-b", "b")).block(WAIT);
            assertEquals(ToolAdmissionDigests.plan(first), ToolAdmissionDigests.plan(second));
            assertEquals(before, tables.stream().map(f::count).toList());
            assertEquals(0, f.guards.get());
            assertEquals(0, f.wakeups.get());
        }
    }

    @Test
    public void previewDoesNotWaitForConcurrentBindingAndPublicationWriteLocks() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var writes = new TransactionTemplate(new DataSourceTransactionManager(f.source));
            writes.execute(status -> {
                f.jdbc.queryForList("SELECT binding_key FROM arte_ai_tool_binding FOR UPDATE");
                f.jdbc.queryForList("SELECT tool_key FROM arte_ai_tool_release FOR UPDATE");
                var preview = CompletableFuture.supplyAsync(() -> f.service.preview(f.input(), f.context("preview", "unused")).block(WAIT));
                try {
                    assertNotNull(preview.get(3, TimeUnit.SECONDS));
                } catch (Exception failure) {
                    throw new AssertionError("Read-only preview must not acquire concurrent governance write locks", failure);
                } finally {
                    preview.cancel(true);
                }
                return null;
            });
            assertEquals(0, f.count("arte_ai_invocation"));
        }
    }

    @Test
    public void matchingPreviewAcceptsAndSameKeyReplaysOriginalAfterConfigurationChange() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var digest = ToolAdmissionDigests.plan(f.service.preview(f.input(), f.context("preview", "unused")).block(WAIT));
            var first = f.service.submit(f.input(), digest, f.context("first", "key")).block(WAIT);
            f.changeConfiguration();
            assertEquals(first, f.service.submit(f.input(), digest, f.context("replay", "key")).block(WAIT));
            assertEquals(1, f.count("arte_ai_invocation"));
            assertEquals(0, f.count("arte_ai_reservation"));
        }
    }

    @Test
    public void stalePreviewRejectsBeforeSavingResolutionOrInvocation() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var digest = ToolAdmissionDigests.plan(f.service.preview(f.input(), f.context("preview", "unused")).block(WAIT));
            f.changeConfiguration();
            var failure = assertThrows(AdmissionException.class, () -> f.service.submit(f.input(), digest, f.context("new", "key")).block(WAIT));
            assertEquals(ResultCodeEnum.AI_TOOL_PLAN_CHANGED, failure.getResultCode());
            assertEquals(0, f.count("arte_ai_invocation"));
            assertEquals(0, f.count("arte_ai_tool_binding_resolution"));
        }
    }

    @Test
    public void changedArgumentsOrLimitsCannotReuseConfirmedPlan() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var digest = ToolAdmissionDigests.plan(f.service.preview(f.input(), f.context("preview", "unused")).block(WAIT));
            var inputs = List.of(new ToolAdmissionInput(f.binding.bindingId(), object("text", text("changed")), null, null, null),
                    new ToolAdmissionInput(f.binding.bindingId(), object(), Duration.ofSeconds(2), 1, 1024L));
            for (var input : inputs) {
                var failure = assertThrows(AdmissionException.class, () -> f.service.submit(input, digest, f.context("new", "key")).block(WAIT));
                assertEquals(ResultCodeEnum.AI_TOOL_PLAN_CHANGED, failure.getResultCode());
            }
            assertEquals(0, f.count("arte_ai_invocation"));
        }
    }

    @Test
    public void differentPreviewCannotConfirmAlreadyAcceptedSameKey() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var old = ToolAdmissionDigests.plan(f.service.preview(f.input(), f.context("preview", "unused")).block(WAIT));
            f.service.submit(f.input(), old, f.context("first", "key")).block(WAIT);
            f.changeConfiguration();
            var next = ToolAdmissionDigests.plan(f.service.preview(f.input(), f.context("preview2", "unused")).block(WAIT));
            assertNotEquals(old, next);
            var failure = assertThrows(AdmissionException.class, () -> f.service.submit(f.input(), next, f.context("new", "key")).block(WAIT));
            assertEquals(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT, failure.getResultCode());
            assertEquals(1, f.count("arte_ai_invocation"));
        }
    }

    @Test
    public void previewIsNotAnAuthorizationCache() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var digest = ToolAdmissionDigests.plan(f.service.preview(f.input(), f.context("preview", "unused")).block(WAIT));
            f.jdbc.update("UPDATE tool_test_permission SET enabled=false");
            assertThrows(AccessDeniedException.class, () -> f.service.submit(f.input(), digest, f.context("new", "key")).block(WAIT));
            assertThrows(AccessDeniedException.class, () -> f.service.preview(f.input(), f.context("again", "unused")).block(WAIT));
            assertEquals(0, f.count("arte_ai_invocation"));
        }
    }
}
