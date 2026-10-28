package com.arte.app.platform.persistence;

import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.arte.ainew.application.tool.discovery.StaticToolDiscovery;
import com.arte.ainew.config.NewAiExecutionProperties;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.ExecutionOwner;
import com.arte.core.pojo.platform.TrustedActor;

import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Scheduler;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 正式宿主装配和真实文本工具，通过公开 MVC 入口验证；可在独立 MySQL 库运行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 11:18 ✾
 */
class PlatformToolExecutionIntegrationTest {
    @Test
    void concurrentSameKeySubmissionsReauthorizeAndKeepOneInvocation() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            rig.context().run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                var command = rig.command(mvc, rig.prepare(mvc), "concurrent replay😀");
                var body = rig.json.writeValueAsString(command);
                var key = UUID.randomUUID().toString();
                var start = new CountDownLatch(1);
                try (var pool = Executors.newFixedThreadPool(2)) {
                    var submissions = IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        var result = rig.request(mvc, "tool/invokeTool", body, key);
                        if (result.getResponse().getStatus() != 202 && result.getResolvedException() != null)
                            result.getResolvedException().printStackTrace();
                        assertEquals(202, result.getResponse().getStatus(), result.getResponse().getContentAsString());
                        return rig.json.readTree(result.getResponse().getContentAsString()).get("data").get("executionId").asString();
                    })).toList();
                    start.countDown();
                    var id = submissions.getFirst().get(15, TimeUnit.SECONDS);
                    assertEquals(id, submissions.getLast().get(15, TimeUnit.SECONDS));
                    assertEquals(1, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_invocation", Integer.class));
                    assertEquals(1, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_execution_snapshot", Integer.class));
                    c.getBean(InvocationDispatchWorker.class).pollOnce().block(Duration.ofSeconds(15));
                    assertEquals("SUCCEEDED", rig.post(mvc, "invocation/getInvocationStatus", Map.of("scope", rig.scope, "invocationId", id), null, 200).get("state").asString());
                    assertEquals(1, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class));
                    rig.base.f.jdbc.update("DELETE FROM arte_rbac_relation WHERE target='aiTool:invoke'");
                    rig.post(mvc, "tool/invokeTool", command, key, 403);
                }
            });
        }
    }

    @Test
    void concurrentAdmissionAndCancellationKeepQueueReservationsConsistent() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            rig.context().run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                var command = rig.command(mvc, rig.prepare(mvc), "queue contention");
                String current = rig.post(mvc, "tool/invokeTool", command, UUID.randomUUID().toString(), 202).get("executionId").asString();
                try (var pool = Executors.newFixedThreadPool(2)) {
                    for (int index = 0; index < 8; index++) {
                        var start = new CountDownLatch(1);
                        String cancelled = current;
                        var release = pool.submit(() -> {
                            assertTrue(start.await(5, TimeUnit.SECONDS));
                            return rig.post(mvc, "tool/cancelToolInvocation", Map.of("scope", rig.scope, "invocationId", cancelled), UUID.randomUUID().toString(), 202);
                        });
                        var accept = pool.submit(() -> {
                            assertTrue(start.await(5, TimeUnit.SECONDS));
                            return rig.post(mvc, "tool/invokeTool", command, UUID.randomUUID().toString(), 202).get("executionId").asString();
                        });
                        start.countDown();
                        release.get(15, TimeUnit.SECONDS);
                        current = accept.get(15, TimeUnit.SECONDS);
                        assertEquals(4L, rig.base.f.jdbc.queryForObject("SELECT SUM(reservations) FROM arte_ai_queue_capacity", Long.class));
                        assertEquals(1, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_outbox WHERE queue_reserved=1", Integer.class));
                    }
                }
                rig.post(mvc, "tool/cancelToolInvocation", Map.of("scope", rig.scope, "invocationId", current), UUID.randomUUID().toString(), 202);
                assertEquals(0L, rig.base.f.jdbc.queryForObject("SELECT SUM(reservations) FROM arte_ai_queue_capacity", Long.class));
            });
        }
    }

    @Test
    void claimValidationPreservesExactMessageAndPermitClassComparison() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            if (rig.base.f.mysql != null) {
                // 仅修改本测试的临时表，显式覆盖大小写不敏感环境；普通验收库仍使用 utf8mb4_bin。
                rig.base.f.jdbc.execute("ALTER TABLE arte_ai_outbox MODIFY message_key CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL");
                rig.base.f.jdbc.execute("ALTER TABLE arte_ai_scheduling_permit MODIFY message_key CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL, MODIFY work_class VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL");
            }
            rig.context().run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                rig.post(mvc, "tool/invokeTool", rig.command(mvc, rig.prepare(mvc), "exact claim identity"), "exact-claim", 202);
                var store = (MybatisExecutionPersistence) c.getBean(ExecutionStore.class);
                var type = OutboxMessage.WorkClass.valueOf(rig.base.f.jdbc.queryForObject("SELECT work_class FROM arte_ai_outbox WHERE kind='DISPATCH'", String.class));
                var message = store.claimClass(type, "exact-worker", Duration.ofSeconds(10), 1, rig.tools.manifest()).block(Duration.ofSeconds(15)).getFirst();
                assertTrue(store.validateClaim(message).block(Duration.ofSeconds(15)).successful());
                var forged = new OutboxMessage(message.messageId().toUpperCase(java.util.Locale.ROOT), message.invocationId(), message.owner(),
                        message.kind(), message.eventSequence(), message.workerId(), message.fencingToken(), message.leaseExpiresAt(), type);
                var rejected = store.validateClaim(forged).block(Duration.ofSeconds(15));
                assertFalse(rejected.successful());
                if (rig.base.f.mysql != null) assertEquals(StoreOutcome.Code.LEASE_LOST, rejected.code());
                rig.base.f.jdbc.update("UPDATE arte_ai_scheduling_permit SET work_class=?", type.name().toLowerCase(java.util.Locale.ROOT));
                assertEquals(StoreOutcome.Code.LEASE_LOST, store.validateClaim(message).block(Duration.ofSeconds(15)).code());
                rig.base.f.jdbc.update("UPDATE arte_ai_scheduling_permit SET work_class=?", type.name());
                assertTrue(store.validateClaim(message).block(Duration.ofSeconds(15)).successful());
            });
        }
    }

    @Test
    void unicodeBoundariesAreCountedAndInvalidInputsNeverDispatch() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            rig.context().run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                String binding = rig.prepare(mvc);
                // 默认全局输入上限为 65536 JSON 字节；不能把 Schema 码点上限当作绕过字节限额的承诺。
                var cases = Map.of("", 0, "e\u0301", 2, "👨‍👩‍👧‍👦", 7, "x".repeat(65000), 65000);
                for (var entry : cases.entrySet()) {
                    String id = rig.post(mvc, "tool/invokeTool", rig.command(mvc, binding, entry.getKey()),
                            UUID.randomUUID().toString(), 202).get("executionId").asString();
                    c.getBean(InvocationDispatchWorker.class).pollOnce().block(Duration.ofSeconds(15));
                    String output = rig.post(mvc, "invocation/getInvocationResult", Map.of("scope", rig.scope, "invocationId", id), null, 200)
                            .get("outputJson").asString();
                    assertEquals(entry.getValue(), rig.json.readTree(output).get("codePoints").asInt());
                }
                for (var arguments : List.of(Map.of("text", 12), Map.of("text", "x".repeat(65537)), Map.of("text", "ok", "extra", true)))
                    rig.post(mvc, "tool/previewInvocation", Map.of("scope", rig.scope,
                            "input", Map.of("bindingId", binding, "arguments", arguments)), null, 409);
                assertEquals(4, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class));
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_reservation", Integer.class));
            });
        }
    }

    @Test
    void restartDiscoversBeforeDispatchAndRetainsOriginalInvocationAndResult() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            var id = new AtomicReference<String>();
            var command = new AtomicReference<Map<String, Object>>();
            rig.context().run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                command.set(rig.command(mvc, rig.prepare(mvc), "重启😀"));
                id.set(rig.post(mvc, "tool/invokeTool", command.get(), "restart", 202).get("executionId").asString());
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class));
            });
            // 上一个 Spring 容器已经关闭；只复用物理库，不复用节点发现视图或 Worker。
            rig.context().run(c -> {
                assertNull(c.getStartupFailure());
                assertTrue(c.getBean(StaticToolDiscovery.class).state().discoveryHealthy());
                var mvc = rig.mvc(c);
                c.getBean(InvocationDispatchWorker.class).pollOnce().block(Duration.ofSeconds(15));
                assertEquals(id.get(), rig.post(mvc, "tool/invokeTool", command.get(), "restart", 202).get("executionId").asString());
                assertEquals("{\"codePoints\":3}", rig.post(mvc, "invocation/getInvocationResult",
                        Map.of("scope", rig.scope, "invocationId", id.get()), null, 200).get("outputJson").asString());
                assertEquals(1, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class));
            });
        }
    }

    @Test
    void cancellationBeforeClaimRemainsTerminalAcrossRepeatedControlAndWorkerPolls() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            rig.context().run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                String id = rig.post(mvc, "tool/invokeTool", rig.command(mvc, rig.prepare(mvc), "cancel"), "cancel-call", 202)
                        .get("executionId").asString();
                var query = Map.of("scope", rig.scope, "invocationId", id);
                rig.post(mvc, "tool/cancelToolInvocation", query, "stop", 202);
                rig.post(mvc, "tool/cancelToolInvocation", query, "stop", 202);
                c.getBean(InvocationDispatchWorker.class).pollOnce().block(Duration.ofSeconds(15));
                assertEquals("CANCELLED", rig.post(mvc, "invocation/getInvocationStatus", query, null, 200).get("state").asString());
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class));
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_queue_capacity WHERE reservations<>0", Integer.class));
            });
        }
    }

    @Test
    void accountLifecycleChangeAfterAdmissionCannotRunWithOldExecutionIdentity() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            rig.context().run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                String id = rig.post(mvc, "tool/invokeTool", rig.command(mvc, rig.prepare(mvc), "old-account"), "old-account", 202).get("executionId").asString();
                rig.base.f.jdbc.update("UPDATE arte_rbac_user SET create_time=? WHERE id=1", rig.accountCreatedAt.plusDays(1));
                c.getBean(InvocationDispatchWorker.class).pollOnce().block(Duration.ofSeconds(15));
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class));
                var owner = new ExecutionOwner(rig.scope.tenantId(), rig.scope.workspaceId(), rig.base.f.actor.principal().subjectId());
                assertEquals(Invocation.State.FAILED,
                        c.getBean(ExecutionStore.class).find(owner, id).block(Duration.ofSeconds(15)).state());
            });
        }
    }

    @Test
    void formalAssemblyFirstUseAndTwoWorkersProduceOneFreeResult() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            rig.context().run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                String binding = rig.prepare(mvc);
                var command = rig.command(mvc, binding, "A😀中\n");
                var accepted = rig.post(mvc, "tool/invokeTool", command, "same-call", 202);
                String id = accepted.get("executionId").asString();
                var replay = rig.post(mvc, "tool/invokeTool", command, "same-call", 202);
                assertEquals(id, replay.get("executionId").asString());
                var worker = c.getBean(InvocationDispatchWorker.class);
                // 第二 Worker 具有独立存储适配器／workerId，共用物理库及相同分类策略。
                var store = new MybatisExecutionPersistence(rig.base.f.source, c.getBean(ExecutionRecordCodec.class),
                        c.getBean("newAiPersistenceScheduler", Scheduler.class), () -> {
                }, () -> {
                },
                        c.getBean(NewAiExecutionProperties.class).scheduling(), true);
                var other = new InvocationDispatchWorker(store, store, c.getBean(InvocationCoordinator.class),
                        c.getBean(NewAiExecutionProperties.class), c.getBean("newAiWorkerScheduler", Scheduler.class),
                        null, c.getBean(NewAiExecutionProperties.class).scheduling(),
                        () -> c.getBean(StaticToolDiscovery.class).state().snapshot().checksum());
                CompletableFuture.allOf(CompletableFuture.runAsync(() -> worker.pollOnce().block(Duration.ofSeconds(15))),
                        CompletableFuture.runAsync(() -> other.pollOnce().block(Duration.ofSeconds(15)))).get();
                var query = Map.of("scope", rig.scope, "invocationId", id);
                var status = rig.post(mvc, "invocation/getInvocationStatus", query, null, 200);
                assertEquals("SUCCEEDED", status.get("state").asString());
                assertEquals("NOT_RESERVED", status.get("budgetState").asString());
                assertEquals("{\"codePoints\":4}", rig.post(mvc, "invocation/getInvocationResult", query, null, 200).get("outputJson").asString());
                assertEquals(1, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class));
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_reservation", Integer.class));
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account", Integer.class));
                System.out.println("S209_MANIFEST=" + rig.tools.manifest());
            });
        }
    }

    @Test
    void revokedInvocationPermissionAndDisabledAiDenyWithoutNewExecutionButHistoryRemainsReadable() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            rig.context().run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                var command = rig.command(mvc, rig.prepare(mvc), "original");
                String id = rig.post(mvc, "tool/invokeTool", command, "original", 202).get("executionId").asString();
                rig.base.f.jdbc.update("DELETE FROM arte_rbac_relation WHERE target='aiTool:invoke'");
                rig.post(mvc, "tool/invokeTool", command, "original", 403);
                rig.post(mvc, "tool/invokeTool", command, "new", 403);
                // 已受理任务派发前重新授权失败，不调用算法，历史读取使用独立 READ 资格。
                c.getBean(InvocationDispatchWorker.class).pollOnce().block(Duration.ofSeconds(15));
                assertNotEquals("SUCCEEDED", rig.post(mvc, "invocation/getInvocationStatus", Map.of("scope", rig.scope, "invocationId", id), null, 200).get("state").asString());
                rig.setAiEnabled(false);
                assertNotNull(rig.post(mvc, "invocation/getInvocationStatus", Map.of("scope", rig.scope, "invocationId", id), null, 200));
                assertEquals(1, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_invocation", Integer.class));
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class));
            });
        }
    }

    @Test
    void malformedArgumentsAndForeignWorkspaceCannotReachExecution() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            rig.context().run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                var binding = rig.prepare(mvc);
                rig.post(mvc, "tool/previewInvocation", Map.of("scope", rig.scope,
                        "input", Map.of("bindingId", binding, "arguments", Map.of("wrong", "text"))), null, 409);
                rig.base.f.jdbc.update("INSERT INTO arte_rbac_user VALUES(2,'bob',1,?)", Timestamp.valueOf("2026-10-10 10:00:00"));
                var bob = new TrustedActor(c.getBean(RbacIdentityProvider.class)
                        .resolveAuthenticatedName("bob").orElseThrow(), Instant.now());
                var foreign = rig.base.f.services.personal().initialize(bob, new CommandMeta("bob-init", "test")).value().workspace().scope();
                rig.post(mvc, "tool/previewInvocation", Map.of("scope", foreign,
                        "input", Map.of("bindingId", binding, "arguments", Map.of("text", "secret"))), null, 403);
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_invocation", Integer.class));
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_execution_snapshot", Integer.class));
            });
        }
    }
}
