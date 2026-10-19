package com.arte.ainew.tool.admission;

import com.arte.ainew.serialization.CanonicalJson;
import com.arte.core.diagnostics.OperationObservation;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * 真实 MyBatis 事务的提交、同键重放和故障回滚必须生成不同诊断点，不得混作新的受理提交。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 16:52 ✾
 */
public class ToolAdmissionObservationTest {

    @Test
    public void commitReplayAndRollbackHaveDistinctDurablePoints() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var messages = new CopyOnWriteArrayList<String>();
            var logger = (Logger) LogManager.getLogger(OperationObservation.class);
            var previous = logger.getLevel();
            var capture = new AbstractAppender("tool-tx-points", null, null, false, Property.EMPTY_ARRAY) {
                public void append(LogEvent event) {
                    messages.add(event.getMessage().getFormattedMessage());
                }
            };
            capture.start();
            logger.addAppender(capture);
            logger.setLevel(Level.INFO);
            try {
                var original = f.service.submit(f.input(), f.context("first", "same")).block(Duration.ofSeconds(15));
                assertEquals(original, f.service.submit(f.input(), f.context("replay", "same")).block(Duration.ofSeconds(15)));
                f.jdbc.execute("ALTER TABLE arte_ai_tool_execution_snapshot ADD CONSTRAINT reject_p01 CHECK (invocation_key <> '"
                        + CanonicalJson.key("blocked") + "')");
                assertThrows(RuntimeException.class, () -> f.service.submit(f.input(), f.context("blocked", "new")).block(Duration.ofSeconds(15)));
                var json = JsonMapper.builder().build();
                var points = messages.stream().filter(line -> line.startsWith("AI_TOOL_POINT "))
                        .map(line -> json.readTree(line.substring("AI_TOOL_POINT ".length())).get("stage").asString()).toList();
                assertEquals(List.of("ADMISSION_COMMITTED", "ADMISSION_REPLAY_COMMITTED", "ADMISSION_ROLLED_BACK"), points);
                for (String table : List.of("arte_ai_invocation", "arte_ai_tool_execution_snapshot", "arte_ai_event"))
                    assertEquals(1, f.count(table));
                assertEquals(2, f.count("arte_ai_outbox"));
            } finally {
                logger.removeAppender(capture);
                logger.setLevel(previous);
                capture.stop();
            }
        }
    }
}
