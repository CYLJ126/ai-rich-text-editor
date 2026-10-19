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
