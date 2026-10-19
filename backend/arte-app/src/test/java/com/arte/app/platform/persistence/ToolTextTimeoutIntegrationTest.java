package com.arte.app.platform.persistence;

import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.persistence.mybatis.MybatisToolAdmissionStore;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.ToolDefinition;
import com.arte.ainew.pojo.tool.ToolDefinitionRevision;
import com.arte.ainew.pojo.tool.ToolExecutionPolicy;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.app.platform.ai.tool.LocalTextTools;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 开发期统一 v1 为 30 秒总预算，验证慢 JDBC 闭环及旧固定契约不可由运行时覆盖。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 22:49 ✾
 */
class ToolTextTimeoutIntegrationTest {

    @Test
    void singleV1ContractAndAdapterUseThirtySecondBudget() {
        var tools = new LocalTextTools(Clock.systemUTC());
        assertEquals("v1", tools.revision().tool().definition().version());
        assertEquals(Duration.ofSeconds(30), tools.revision().tool().defaultPolicy().timeout());
        assertEquals(1, tools.definitions().tools().size());
        assertEquals(1, tools.registry().registrations().getFirst().adapters().size());
        assertEquals(tools.revision().tool().discoveredCapability(), tools.registry().findAdapter(LocalTextTools.ADAPTER).orElseThrow().capability());
        assertNotEquals("331756c89f707d42a1142275e1be4d4711b6ef76ef6ac7df4b996ce463cd6569", tools.manifest());
        System.out.println("TEXT_TIMEOUT_MANIFEST=" + tools.manifest());
    }

    @Test
    void slowerJdbcRoundTripsCanCompleteWithinOriginalTotalDeadline() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            var latency = new DelayedJdbc(rig.base.f.source);
            rig.context(latency).run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                String binding = rig.prepare(mvc);
                var input = Map.of("bindingId", binding, "arguments", Map.of("text", "A😀中\n"), "timeoutSeconds", 30);
                var preview = rig.post(mvc, "tool/previewInvocation", Map.of("scope", rig.scope, "input", input), null, 200);
                assertEquals("v1", preview.get("tool").get("version").asString());
                assertEquals(30000, preview.get("timeoutMillis").asLong());
                var command = Map.of("scope", rig.scope, "input", input, "expectedPlanDigest", preview.get("planDigest").asString());
                latency.enabled.set(true);
                long started = System.nanoTime();
                var accepted = rig.post(mvc, "tool/invokeTool", command, "slow-v1", 202);
                String id = accepted.get("executionId").asString();
                var owner = new ExecutionOwner(rig.scope.tenantId(), rig.scope.workspaceId(), rig.base.f.actor.principal().subjectId());
                var snapshots = c.getBean(MybatisToolAdmissionStore.class);
                var before = snapshots.snapshot(owner, id).block(Duration.ofSeconds(15));
                assertNotNull(before);
                var total = Duration.between(before.receivedAt(), before.request().options().deadline());
                assertTrue(total.compareTo(Duration.ofSeconds(5)) > 0);
                assertTrue(total.compareTo(Duration.ofSeconds(30)) <= 0, "The original HTTP entry deadline may further tighten the product budget");
                assertEquals("TOOL_LONG", rig.base.f.jdbc.queryForObject("SELECT work_class FROM arte_ai_outbox WHERE invocation_id=? AND kind='DISPATCH'", String.class, id));
                c.getBean(InvocationDispatchWorker.class).pollOnce().block(Duration.ofSeconds(25));
                long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
                latency.enabled.set(false);
                assertTrue(latency.delays.get() >= 100, "Exercise real JDBC work throughout admission and dispatch");
                assertTrue(elapsed >= 5000, "The full path must exceed the previous five-second development budget");
                var query = Map.of("scope", rig.scope, "invocationId", id);
                var status = rig.post(mvc, "invocation/getInvocationStatus", query, null, 200);
                assertEquals("SUCCEEDED", status.get("state").asString());
                assertEquals("NOT_RESERVED", status.get("budgetState").asString());
                assertEquals("{\"codePoints\":4}", rig.post(mvc, "invocation/getInvocationResult", query, null, 200).get("outputJson").asString());
                assertEquals(accepted, rig.post(mvc, "tool/invokeTool", command, "slow-v1", 202));
                assertEquals(before.request().options().deadline(), snapshots.snapshot(owner, id).block(Duration.ofSeconds(15)).request().options().deadline());
                assertEquals(1, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class));
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_reservation", Integer.class));
                assertEquals(0L, rig.base.f.jdbc.queryForObject("SELECT SUM(reservations) FROM arte_ai_queue_capacity", Long.class));
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_scheduling_permit", Integer.class));
                System.out.println("TEXT_TIMEOUT_DELAYED_JDBC elapsedMs=" + elapsed + " statements=" + latency.delays.get());
            });
        }
    }

    @Test
    void fixedCatalogStillRejectsTheOldFiveSecondContractAtTheSameVersion() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            rig.context().run(c -> {
                assertNull(c.getStartupFailure());
                var current = rig.tools.revision();
                var tool = current.tool();
                var policy = tool.defaultPolicy();
                var old = new ToolDefinition(tool.definition(), tool.providerId(), tool.title(), tool.description(), tool.tags(),
                        tool.capability(), tool.inputSchema(), tool.outputSchema(), tool.configurationSchema(), tool.defaultConfiguration(),
                        tool.capabilities(), tool.risk(), new ToolExecutionPolicy(Duration.ofSeconds(5), policy.maxAttempts(),
                        policy.retryBackoff(), policy.maxInputBytes(), policy.maxOutputBytes(), policy.approvalMode()), "0".repeat(64));
                var signed = new ToolDefinition(old.definition(), old.providerId(), old.title(), old.description(), old.tags(),
                        old.capability(), old.inputSchema(), old.outputSchema(), old.configurationSchema(), old.defaultConfiguration(),
                        old.capabilities(), old.risk(), old.defaultPolicy(), ToolCatalogDigests.tool(old));
                var catalog = c.getBean(ToolCatalogStore.class);
                var outcome = catalog.put(new ToolCatalogStore.Batch(List.of(), List.of(),
                        List.of(new ToolDefinitionRevision(signed, current.provider(), current.target())))).block(Duration.ofSeconds(15));
                assertNotNull(outcome);
                assertEquals(StoreOutcome.Code.IDEMPOTENCY_CONFLICT, outcome.code());
                assertEquals(current, catalog.findTool(LocalTextTools.TOOL).block(Duration.ofSeconds(15)));
            });
        }
    }

    private static final class DelayedJdbc extends DelegatingDataSource {
        final AtomicBoolean enabled = new AtomicBoolean();
        final AtomicInteger delays = new AtomicInteger();

        DelayedJdbc(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            var connection = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                var result = invoke(method, connection, args);
                if (!method.getName().equals("prepareStatement")) return result;
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (statement, operation, values) -> {
                    if (operation.getName().startsWith("execute") && enabled.get()) {
                        delays.incrementAndGet();
                        Thread.sleep(50);
                    }
                    return invoke(operation, result, values);
                });
            });
        }

        private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        }
    }
}
