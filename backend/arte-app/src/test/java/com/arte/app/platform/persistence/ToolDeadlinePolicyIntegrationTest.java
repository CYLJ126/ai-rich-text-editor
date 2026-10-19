package com.arte.app.platform.persistence;

import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.config.NewAiExecutionProperties;
import com.arte.ainew.persistence.mybatis.ExecutionSqlSessionFactory;
import com.arte.ainew.persistence.mybatis.MybatisToolAdmissionStore;
import com.arte.ainew.persistence.mybatis.mapper.SystemMapper;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.app.platform.ai.tool.LocalTextTools;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P06 验证真实文本工具的期限求交、固定分类及过期后的确认／查询；支持独立 H2 或 MySQL。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 22:21 ✾
 */
class ToolDeadlinePolicyIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Test
    void databaseClockRetainsMillisecondsUsedByDeadlineAndLeaseChecks() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            var system = new SqlSessionTemplate(ExecutionSqlSessionFactory.create(rig.base.f.source)).getMapper(SystemMapper.class);
            if (rig.base.f.mysql != null) {
                new TransactionTemplate(new DataSourceTransactionManager(rig.base.f.source)).executeWithoutResult(status -> {
                    // 仅固定本测试事务的数据库会话时间，证明读取 .789 而不是整秒；不修改服务器时钟。
                    rig.base.f.jdbc.execute("SET timestamp=1792418651.789");
                    try {
                        assertEquals(Instant.ofEpochSecond(1792418651, 789_000_000), system.databaseTime().toInstant());
                    } finally {
                        rig.base.f.jdbc.execute("SET timestamp=DEFAULT");
                    }
                });
            } else {
                boolean fractional = false;
                for (int index = 0; index < 10; index++) {
                    var at = system.databaseTime();
                    assertEquals(0, at.getNanos() % 1_000_000);
                    fractional |= at.getNanos() != 0;
                    if (fractional) break;
                    Thread.sleep(2);
                }
                assertTrue(fractional, "Database milliseconds must not be truncated to seconds");
            }
        }
    }

    @Test
    void previewAndAcceptedDeadlineUseSameCapsWithoutChangingFixedToolOrManifest() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            rig.context().withPropertyValues("arte.ai-new.limits.maximum-timeout=2s").run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                String binding = rig.prepare(mvc);
                var owner = new ExecutionOwner(rig.scope.tenantId(), rig.scope.workspaceId(), rig.base.f.actor.principal().subjectId());
                var policy = c.getBean(NewAiExecutionProperties.class).scheduling();
                assertEquals(Duration.ofSeconds(5), rig.tools.revision().tool().defaultPolicy().timeout());
                assertEquals("v1", LocalTextTools.TOOL.version());
                assertEquals(rig.tools.manifest(), policy.toolManifest());
                assertTrue(Duration.ofSeconds(5).compareTo(policy.shortThreshold()) <= 0);
                for (int requested : new int[]{30, 1}) {
                    var input = Map.of("bindingId", binding, "arguments", Map.of("text", "deadline"), "timeoutSeconds", requested);
                    var preview = rig.post(mvc, "tool/previewInvocation", Map.of("scope", rig.scope, "input", input), null, 200);
                    long cap = Math.min(2, requested) * 1000L;
                    assertEquals(cap, preview.get("timeoutMillis").asLong());
                    var command = Map.of("scope", rig.scope, "input", input, "expectedPlanDigest", preview.get("planDigest").asString());
                    String id = rig.post(mvc, "tool/invokeTool", command, UUID.randomUUID().toString(), 202).get("executionId").asString();
                    var snapshot = c.getBean(MybatisToolAdmissionStore.class).snapshot(owner, id).block(WAIT);
                    assertNotNull(snapshot);
                    assertEquals(Duration.ofMillis(cap), snapshot.request().options().requestedTimeout());
                    assertTrue(snapshot.request().options().deadline().isAfter(snapshot.receivedAt()));
                    assertFalse(snapshot.request().options().deadline().isAfter(snapshot.receivedAt().plusMillis(cap)));
                    assertEquals(snapshot.request().context().deadline(), snapshot.request().options().deadline());
                    assertEquals("TOOL_SHORT", rig.base.f.jdbc.queryForObject("SELECT work_class FROM arte_ai_outbox WHERE invocation_id=? AND kind='DISPATCH'", String.class, id));
                    assertTrue(rig.base.f.jdbc.queryForObject("SELECT queue_deadline FROM arte_ai_outbox WHERE invocation_id=? AND kind='DISPATCH'", Long.class, id)
                            <= snapshot.request().options().deadline().toEpochMilli());
                }
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class));
            });
        }
    }

    @Test
    void expiredOriginalCanBeConfirmedAndReadWithoutNewDeadlineAttemptOrDispatch() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            rig.context().run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                var input = Map.of("bindingId", rig.prepare(mvc), "arguments", Map.of("text", "expired original"), "timeoutSeconds", 1);
                var preview = rig.post(mvc, "tool/previewInvocation", Map.of("scope", rig.scope, "input", input), null, 200);
                var command = Map.of("scope", rig.scope, "input", input, "expectedPlanDigest", preview.get("planDigest").asString());
                var accepted = rig.post(mvc, "tool/invokeTool", command, "original", 202);
                String id = accepted.get("executionId").asString();
                var owner = new ExecutionOwner(rig.scope.tenantId(), rig.scope.workspaceId(), rig.base.f.actor.principal().subjectId());
                var store = c.getBean(ExecutionStore.class);
                var original = store.find(owner, id).block(WAIT);
                assertNotNull(original);
                var deadline = original.request().options().deadline();
                long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (Instant.now().isBefore(deadline.plusMillis(10))) {
                    assertTrue(System.nanoTime() < until, "Original deadline must elapse");
                    Thread.sleep(10);
                }
                // 查询与确认使用本次请求的授权／等待期限，不重新获得执行资格。
                var query = Map.of("scope", rig.scope, "invocationId", id);
                assertEquals("ACCEPTED", rig.post(mvc, "invocation/getInvocationStatus", query, null, 200).get("state").asString());
                assertEquals(accepted, rig.post(mvc, "tool/invokeTool", command, "original", 202));
                assertEquals(deadline, store.find(owner, id).block(WAIT).request().options().deadline());
                assertEquals(1, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_outbox WHERE kind='DISPATCH'", Integer.class));
                c.getBean(InvocationDispatchWorker.class).pollOnce().block(WAIT);
                assertEquals("TIMED_OUT", rig.post(mvc, "invocation/getInvocationStatus", query, null, 200).get("state").asString());
                assertEquals(accepted, rig.post(mvc, "tool/invokeTool", command, "original", 202));
                var after = store.find(owner, id).block(WAIT);
                assertEquals(Invocation.State.TIMED_OUT, after.state());
                assertEquals(deadline, after.request().options().deadline());
                assertEquals(original.acceptedAt(), after.acceptedAt());
                assertEquals(1, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_invocation", Integer.class));
                assertEquals(1, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_outbox WHERE kind='DISPATCH'", Integer.class));
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class));
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_reservation", Integer.class));
            });
        }
    }
}
