package com.arte.app.platform.persistence;

import com.alibaba.druid.pool.DruidDataSource;
import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.arte.app.diagnostics.NewAiHttpObservationFilter;
import com.arte.app.diagnostics.NewAiJdbcObservationFilter;
import com.arte.app.diagnostics.NewAiObservationConfiguration;
import com.arte.core.diagnostics.OperationObservation;
import com.arte.core.diagnostics.RequestObservation;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 正式宿主／Druid／MVC／真实工具验证阶段 SQL；可显式在独立 MySQL 采集 10 次预热＋50 次串行样本。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 16:52 ✾
 */
class ToolPhaseObservationIntegrationTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void endToEndStagesRetainSqlAndOriginalTrace() throws Exception {
        boolean p02 = Boolean.getBoolean("platform.tool.p02.baseline");
        boolean p03 = Boolean.getBoolean("platform.tool.p03.baseline");
        boolean p04 = Boolean.getBoolean("platform.tool.p04.baseline");
        boolean baseline = Boolean.getBoolean("platform.tool.p01.baseline") || p02 || p03 || p04;
        final String outputPrefix;
        if (p04) outputPrefix = "target/tool-p04";
        else if (p03) outputPrefix = "target/tool-p03";
        else if (p02) outputPrefix = "target/tool-p02";
        else outputPrefix = "target/tool-p01";
        if (baseline) assertNotNull(System.getProperty("platform.test.mysql"), "Baseline requires isolated MySQL");
        var events = new CopyOnWriteArrayList<String>();
        var logger = (Logger) LogManager.getLogger(OperationObservation.class);
        var previous = logger.getLevel();
        var httpLogger = (Logger) LogManager.getLogger(NewAiHttpObservationFilter.class);
        var httpPrevious = httpLogger.getLevel();
        var capture = new AbstractAppender("tool-phase-proof", null, null, false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.getMessage().getFormattedMessage());
            }
        };
        capture.start();
        logger.addAppender(capture);
        logger.setLevel(Level.INFO);
        httpLogger.addAppender(capture);
        httpLogger.setLevel(Level.INFO);
        try (var rig = new ToolAcceptanceRig(); var dataSource = new DruidDataSource()) {
            dataSource.setUrl(rig.base.f.source.getUrl());
            dataSource.setUsername(rig.base.f.source.getUsername());
            dataSource.setPassword(rig.base.f.source.getPassword());
            dataSource.setInitialSize(1);
            dataSource.setMaxActive(8);
            dataSource.setProxyFilters(List.of(new NewAiJdbcObservationFilter()));
            dataSource.init();
            var runner = rig.context(dataSource).withUserConfiguration(NewAiObservationConfiguration.class);
            if (baseline) runner = runner.withPropertyValues("arte.ai-new-execution.worker-enabled=true");
            runner.run(c -> {
                assertNull(c.getStartupFailure());
                // 宿主启动可能重载日志配置；在启动完成后启用本测试采集，避免将日志关闭误判为零 SQL。
                logger.addAppender(capture);
                logger.setLevel(Level.INFO);
                httpLogger.addAppender(capture);
                httpLogger.setLevel(Level.INFO);
                var mvc = rig.mvc(c, true);
                rig.publish(mvc);
                var command = rig.command(mvc, rig.prepare(mvc), "private-text-never-in-phase-log😀");
                var measuredIds = new ArrayList<String>();
                String lastKey = null;
                int warmups = baseline ? 10 : 0, samples = baseline ? 50 : 1;
                events.clear();
                for (int index = 0; index < warmups + samples; index++) {
                    if (index == warmups) events.clear();
                    lastKey = UUID.randomUUID().toString();
                    var accepted = rig.request(mvc, "tool/invokeTool", rig.json.writeValueAsString(command), lastKey);
                    assertEquals(202, accepted.getResponse().getStatus(), accepted.getResponse().getContentAsString());
                    String id = rig.json.readTree(accepted.getResponse().getContentAsString()).get("data").get("executionId").asString();
                    var http = (RequestObservation) accepted.getRequest().getAttribute(NewAiHttpObservationFilter.ATTRIBUTE);
                    var admissionStages = rows(events, "AI_TOOL_STAGE ").stream().filter(row ->
                            id.equals(row.get("invocationId").asString()) && Set.of("ENTRY_AUTHORIZATION", "ADMISSION").contains(row.get("stage").asString())).toList();
                    assertEquals(2, admissionStages.size(), "Expected entry/admission rows; captured=" + events.size());
                    assertEquals(((Number) http.report().get("sqlCount")).longValue(),
                            admissionStages.stream().mapToLong(row -> row.get("sqlCount").asLong()).sum(), "Stage SQL must reconcile with HTTP SQL exactly");
                    if (index >= warmups) measuredIds.add(id);
                    if (!baseline) c.getBean(InvocationDispatchWorker.class).pollOnce().block(Duration.ofSeconds(15));
                    else {
                        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                        while (!"SUCCEEDED".equals(rig.post(mvc, "invocation/getInvocationStatus", Map.of("scope", rig.scope, "invocationId", id), null, 200).get("state").asString())) {
                            assertTrue(System.nanoTime() < until, "Automatic Worker must complete the invocation");
                            Thread.sleep(100);
                        }
                    }
                    assertEquals("SUCCEEDED", rig.post(mvc, "invocation/getInvocationStatus", Map.of("scope", rig.scope, "invocationId", id), null, 200).get("state").asString());
                }
                var stages = rows(events, "AI_TOOL_STAGE ");
                var points = rows(events, "AI_TOOL_POINT ");
                Set<String> required = Set.of("ENTRY_AUTHORIZATION", "ADMISSION", "REPLAY_LOOKUP", "BINDING_RESOLUTION", "SCHEMA_VALIDATION",
                        "HOST_AUTHORIZATION", "ADMISSION_TRANSACTION", "COMMIT_AUTHORIZATION", "COMMIT_GOVERNANCE", "COMMIT_WRITES",
                        "WORKER_LOAD", "DISPATCH_ROUTING", "DISPATCH_LOAD", "PREFLIGHT", "PREPARE_DISPATCH", "GATEWAY_PREFLIGHT", "ADAPTER", "RESULT_PERSIST", "TERMINAL_COMMIT");
                for (String id : measuredIds) {
                    var invocationStages = stages.stream().filter(row -> id.equals(row.get("invocationId").asString())).toList();
                    assertTrue(invocationStages.stream().map(row -> row.get("stage").asString()).collect(java.util.stream.Collectors.toSet()).containsAll(required));
                    String trace = invocationStages.getFirst().get("traceId").asString();
                    assertTrue(invocationStages.stream().allMatch(row -> trace.equals(row.get("traceId").asString())));
                    for (String stage : List.of("ENTRY_AUTHORIZATION", "COMMIT_AUTHORIZATION", "COMMIT_GOVERNANCE", "COMMIT_WRITES", "WORKER_LOAD", "PREFLIGHT", "RESULT_PERSIST", "TERMINAL_COMMIT")) {
                        var row = invocationStages.stream().filter(value -> stage.equals(value.get("stage").asString())).findFirst().orElseThrow();
                        assertTrue(row.get("jdbcObserved").asBoolean(), stage + " must see real JDBC events");
                        assertTrue(row.get("sqlCount").asLong() > 0, stage);
                        assertEquals(0L, row.get("sqlInFlight").asLong());
                        assertTrue(row.get("lockWaitMs").isNull());
                    }
                    // P01 宿主预检／最终事务授权分别为 20／18；入口的独立只读快照不纳入本次复用。
                    for (var limit : Map.of("HOST_AUTHORIZATION", 20L, "COMMIT_AUTHORIZATION", 18L).entrySet()) {
                        var row = invocationStages.stream().filter(value -> limit.getKey().equals(value.get("stage").asString())).findFirst().orElseThrow();
                        assertTrue(row.get("jdbcObserved").asBoolean());
                        assertTrue(row.get("sqlCount").asLong() < limit.getValue(), limit.getKey() + " authorization SQL must decrease");
                    }
                    for (var expected : Map.of("BINDING_RESOLUTION", 4L, "SCHEMA_VALIDATION", 0L).entrySet()) {
                        var row = invocationStages.stream().filter(value -> expected.getKey().equals(value.get("stage").asString())).findFirst().orElseThrow();
                        assertEquals(expected.getValue().longValue(), row.get("sqlCount").asLong(), expected.getKey());
                    }
                    var writes = invocationStages.stream().filter(value -> "COMMIT_WRITES".equals(value.get("stage").asString())).findFirst().orElseThrow();
                    if (baseline)
                        assertEquals(17L, writes.get("sqlCount").asLong(), "Warm P04 writes must decrease from 26 to 17");
                    else assertTrue(writes.get("sqlCount").asLong() <= 19, "Cold P04 writes may create counters/pool");
                    var governance = invocationStages.stream().filter(value -> "COMMIT_GOVERNANCE".equals(value.get("stage").asString())).findFirst().orElseThrow();
                    assertTrue(governance.get("sqlCount").asLong() < 13, "Material SQL must decrease, including cold plan insertion");
                    assertTrue(points.stream().anyMatch(row -> id.equals(row.get("invocationId").asString()) && "ADMISSION_COMMITTED".equals(row.get("stage").asString())));
                }
                assertFalse(String.join("\n", events).contains("private-text-never-in-phase-log"));
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_reservation", Integer.class));
                Files.writeString(Path.of(outputPrefix + "-stage-events.log"), String.join("\n", events) + "\n");
                Files.writeString(Path.of(outputPrefix + "-stage-proof.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("warmupCount", warmups, "sampleCount", samples, "database", rig.base.f.mysql == null ? "isolated-h2" : "isolated-mysql",
                                "automaticWorker", baseline, "jvm", System.getProperty("java.version"), "invocationIds", measuredIds, "stages", stages, "points", points)));
                if (!baseline) {
                    events.clear();
                    var replay = rig.request(mvc, "tool/invokeTool", rig.json.writeValueAsString(command), lastKey);
                    assertEquals(202, replay.getResponse().getStatus());
                    String originalId = measuredIds.getLast(), replayTrace = replay.getResponse().getHeader("X-Arte-Trace-Id");
                    var replayStages = rows(events, "AI_TOOL_STAGE ");
                    assertTrue(replayStages.stream().allMatch(row -> replayTrace.equals(row.get("traceId").asString())));
                    var admission = replayStages.stream().filter(row -> "ADMISSION".equals(row.get("stage").asString())).findFirst().orElseThrow();
                    assertEquals(originalId, admission.get("invocationId").asString());
                    assertNotEquals(originalId, admission.get("requestedInvocationId").asString());
                    assertTrue(rows(events, "AI_TOOL_POINT ").stream().anyMatch(row -> replayTrace.equals(row.get("traceId").asString())
                            && "ADMISSION_REPLAY_COMMITTED".equals(row.get("stage").asString())));
                }
            });
        } finally {
            logger.removeAppender(capture);
            logger.setLevel(previous);
            httpLogger.removeAppender(capture);
            httpLogger.setLevel(httpPrevious);
            capture.stop();
        }
    }

    private List<JsonNode> rows(List<String> events, String prefix) {
        return events.stream().filter(value -> value.startsWith(prefix)).map(value -> json.readTree(value.substring(prefix.length()))).toList();
    }
}
