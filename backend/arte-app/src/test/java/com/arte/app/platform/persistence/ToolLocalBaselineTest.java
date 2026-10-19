package com.arte.app.platform.persistence;

import com.arte.ainew.application.execution.InvocationDispatchWorker;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 显式运行的小样本串行基线；包含真实 MVC／平台授权／SQL，不替代 W1—W4 生产压力验收。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 11:18 ✾
 */
class ToolLocalBaselineTest {

    @Test
    void admissionSqlRoundTrips() throws Exception {
        assertNotNull(System.getProperty("platform.test.mysql"), "SQL baseline requires isolated MySQL");
        try (var rig = new ToolAcceptanceRig()) {
            rig.context().run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                var command = rig.command(mvc, rig.prepare(mvc), "SQL 往返计数😀");
                var before = sqlCounts(rig);
                long started = System.nanoTime();
                rig.post(mvc, "tool/invokeTool", command, "sql-count", 202);
                double elapsed = (System.nanoTime() - started) / 1e6;
                var delta = new TreeMap<String, Long>();
                sqlCounts(rig).forEach((sql, count) -> {
                    long difference = count - before.getOrDefault(sql, 0L);
                    if (difference > 0) delta.put(sql, difference);
                });
                long policyReads = delta.entrySet().stream()
                        .filter(e -> e.getKey().startsWith("SELECT") && e.getKey().contains("`arte_platform_workspace_policy`")
                                && e.getKey().contains("FOR UPDATE"))
                        .mapToLong(Map.Entry::getValue).sum();
                // 入口授权和最终受理事务各一次当前事实锁读，保留两个边界，消除各自内部的重复轮次。
                assertEquals(2, policyReads);
                long statements = delta.values().stream().mapToLong(Long::longValue).sum();
                String report = rig.json.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("admissionBusinessSqlCount", statements, "currentWorkspacePolicyReads", policyReads,
                                "scope", "SQL referencing arte_* tables; excludes session, transaction and database-clock statements",
                                "admissionMs", elapsed, "statements", delta));
                Files.writeString(Path.of("target/tool-admission-sql-baseline.json"), report);
                System.out.println("TOOL_ADMISSION_SQL_BASELINE=" + report);
            });
        }
    }

    private static Map<String, Long> sqlCounts(ToolAcceptanceRig rig) {
        var counts = new HashMap<String, Long>();
        // 只读取该随机测试库的归一化计数，不清空全局统计，不记录 SQL 参数。
        rig.base.f.jdbc.query("SELECT DIGEST_TEXT,COUNT_STAR FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=?",
                rows -> {
                    String sql = rows.getString(1);
                    if (sql != null && sql.contains("`arte_") && !sql.contains("performance_schema"))
                        counts.merge(sql, rows.getLong(2), Long::sum);
                }, rig.base.f.database);
        return counts;
    }

    @Test
    void baseline() throws Exception {
        assertNotNull(System.getProperty("platform.test.mysql"), "Baseline requires isolated MySQL");
        try (var rig = new ToolAcceptanceRig()) {
            rig.context().run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                String binding = rig.prepare(mvc);
                var worker = c.getBean(InvocationDispatchWorker.class);
                var samples = new LinkedHashMap<String, List<Double>>();
                for (var name : List.of("preview", "admission", "status", "endToEnd"))
                    samples.put(name, new ArrayList<>());
                Map<String, Double> cold = new LinkedHashMap<>();
                for (int i = 0; i < 61; i++) {
                    long beforePreview = System.nanoTime();
                    var command = rig.command(mvc, binding, "验收😀文本".repeat(100));
                    long beforeAdmission = System.nanoTime();
                    String id = rig.post(mvc, "tool/invokeTool", command, UUID.randomUUID().toString(), 202).get("executionId").asString();
                    long accepted = System.nanoTime();
                    // 主动推进一次，不含生产 Worker 轮询间隔；纳入真实领取、执行、终态写入和查询。
                    worker.pollOnce().block(Duration.ofSeconds(15));
                    long beforeStatus = System.nanoTime();
                    assertEquals("SUCCEEDED", rig.post(mvc, "invocation/getInvocationStatus",
                            Map.of("scope", rig.scope, "invocationId", id), null, 200).get("state").asString());
                    long finished = System.nanoTime();
                    double[] values = {(beforeAdmission - beforePreview) / 1e6, (accepted - beforeAdmission) / 1e6,
                            (finished - beforeStatus) / 1e6, (finished - beforeAdmission) / 1e6};
                    int column = 0;
                    for (var name : samples.keySet()) {
                        if (i == 0) cold.put(name, values[column]);
                        if (i > 10) samples.get(name).add(values[column]);
                        column++;
                    }
                }
                var measured = new LinkedHashMap<String, Object>();
                samples.forEach((name, times) -> {
                    times.sort(Double::compare);
                    measured.put(name, Map.of("p50Ms", percentile(times, .5), "p95Ms", percentile(times, .95), "p99Ms", percentile(times, .99)));
                });
                var report = Map.of("sampleCount", 50, "warmupCount", 10, "coldMs", cold, "latency", measured,
                        "manifest", rig.tools.manifest(), "jvm", System.getProperty("java.version"), "os", System.getProperty("os.name"),
                        "database", rig.base.f.jdbc.queryForObject("SELECT VERSION()", String.class));
                String content = rig.json.writerWithDefaultPrettyPrinter().writeValueAsString(report);
                Files.writeString(Path.of("target/tool-s209-baseline.json"), content);
                System.out.println("S209_BASELINE=" + content);
                assertEquals(61, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class));
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_reservation", Integer.class));
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_queue_capacity WHERE reservations<>0", Integer.class));
            });
        }
    }

    private static double percentile(List<Double> values, double fraction) {
        return values.get((int) Math.ceil(values.size() * fraction) - 1);
    }
}
