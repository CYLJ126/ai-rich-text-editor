package com.arte.app.platform.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 HTTP、只读入口、公共门控、RBAC 和查询存储；验证授权 SQL 不随记录逐条增长。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/22 14:40 ✾
 */
class ToolObservationAccessIntegrationTest {

    @Test
    void listAndStatisticsShareOwnerChecksWithinEachPhaseAndRecheckCurrentPermissions() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            var latency = new AtomicLong();
            var recorded = new DatabaseAiControlIntegrationTest.SqlRecorder(delayed(rig.base.f.source, latency));
            rig.context(recorded).withPropertyValues("arte.ai-new-execution.scheduling.subject-queued=150",
                    "arte.ai-new-execution.scheduling.workspace-queued=150").run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                var command = rig.command(mvc, rig.prepare(mvc), "observation");
                var from = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
                for (int n = 0; n < 100; n++) rig.post(mvc, "tool/invokeTool", command, "observation-" + n, 202);
                var body = Map.of("scope", rig.scope, "from", from.toString(),
                        "until", Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS).toString());
                recorded.sql.clear();
                var page = rig.post(mvc, "tool/listExecutions", body, null, 200);
                assertEquals(10, page.path("records").size());
                assertEquals(50, page.path("scanned").asInt());
                assertEquals(2, workspaceLocks(recorded));
                int listQueries = recorded.sql.size();
                assertTrue(listQueries <= 90, "List SQL must stay bounded: " + listQueries);
                recorded.sql.clear();
                var statistics = rig.post(mvc, "tool/getStatistics", body, null, 200);
                assertEquals(100, statistics.path("invocations").asInt());
                assertEquals(2, workspaceLocks(recorded));
                int statisticsQueries = recorded.sql.size();
                assertTrue(statisticsQueries <= 100, "Statistics SQL must stay bounded: " + statisticsQueries);
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class));

                // 按现场约 50ms 的 SQL 往返成本构造慢库；页面会同时请求列表与统计，总期限仍为 10 秒。
                recorded.sql.clear();
                latency.set(50);
                long started = System.nanoTime();
                try (var pool = Executors.newFixedThreadPool(2)) {
                    var listRequest = pool.submit(() -> rig.post(mvc, "tool/listExecutions", body, null, 200));
                    var statsRequest = pool.submit(() -> rig.post(mvc, "tool/getStatistics", body, null, 200));
                    assertEquals(10, listRequest.get(12, TimeUnit.SECONDS).path("records").size());
                    assertEquals(100, statsRequest.get(12, TimeUnit.SECONDS).path("invocations").asInt());
                } finally {
                    latency.set(0);
                }
                long concurrentMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertEquals(4, workspaceLocks(recorded));

                // 批量允许不跨查询缓存；撤销当前操作后不能继续返回调用或统计事实。
                rig.base.f.jdbc.update("DELETE FROM arte_rbac_relation WHERE source='alice' AND binding_type='user_to_operation' AND target='aiTool:list'");
                assertEquals(0, rig.post(mvc, "tool/listExecutions", body, null, 200).path("records").size());
                assertEquals(0, rig.post(mvc, "tool/getStatistics", body, null, 200).path("invocations").asInt());
                System.out.println("TOOL_OBSERVATION_SQL list50=" + listQueries + " statistics100=" + statisticsQueries
                        + " workspaceLocksPerRequest=2 concurrentWith50msSqlMillis=" + concurrentMillis);
            });
        }
    }

    private long workspaceLocks(DatabaseAiControlIntegrationTest.SqlRecorder recorded) {
        return recorded.sql.stream().map(sql -> sql.replaceAll("\\s+", " ").toUpperCase())
                .filter(sql -> sql.contains("FROM ARTE_PLATFORM_WORKSPACE WHERE") && sql.contains("FOR UPDATE")).count();
    }

    private javax.sql.DataSource delayed(javax.sql.DataSource source, AtomicLong latency) {
        return new org.springframework.jdbc.datasource.DelegatingDataSource(source) {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection = super.getConnection();
                return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("prepareStatement") && latency.get() > 0)
                                Thread.sleep(latency.get());
                            try {
                                return method.invoke(connection, args);
                            } catch (java.lang.reflect.InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
            }
        };
    }
}
