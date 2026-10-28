package com.arte.ainew.tool.execution;

import com.arte.ainew.application.tool.chat.ToolChatCoordinator;
import com.arte.ainew.application.tool.chat.ToolChatWorker;
import com.arte.ainew.config.NewAiToolChatProperties;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.execution.ResultRef;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.generation.ModelResult;
import com.arte.ainew.pojo.tool.ToolChatRun;
import com.arte.ainew.pojo.tool.ToolChatRunSnapshot;
import com.arte.ainew.pojo.tool.ToolChatSubmission;
import com.arte.ainew.spi.persistence.ExecutionResultStore;
import com.arte.ainew.spi.persistence.ToolChatRunStore;
import com.arte.ainew.web.ConversationExceptionHandler;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import reactor.core.publisher.Mono;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/**
 * 捕获真实日志事件，验证诊断字段、脱敏栈及执行语义；不连接供应商或业务库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 21:38 ✾
 */
public class FailureDiagnosticsTest {

    static final class Capture implements AutoCloseable {
        final List<LogEvent> events = new CopyOnWriteArrayList<>();
        final Map<Logger, Level> levels = new HashMap<>();
        final AbstractAppender appender = new AbstractAppender("failure-diagnostics-" + UUID.randomUUID(), null, null, false, Property.EMPTY_ARRAY) {
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };

        Capture(Class<?>... types) {
            appender.start();
            for (var type : types) {
                var logger = (Logger) LogManager.getLogger(type);
                levels.put(logger, logger.getLevel());
                logger.addAppender(appender);
                logger.setLevel(Level.WARN);
            }
        }

        LogEvent find(String marker) {
            return events.stream().filter(e -> e.getMessage().getFormattedMessage().contains(marker)).findFirst().orElseThrow();
        }

        public void close() {
            levels.forEach((logger, level) -> {
                logger.removeAppender(appender);
                logger.setLevel(level);
            });
            appender.stop();
        }
    }

    static String rendered(LogEvent event) {
        var out = new StringWriter();
        out.append(event.getMessage().getFormattedMessage());
        if (event.getThrown() != null) event.getThrown().printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    @Test
    public void httpUnexpectedFailureHasSafeCauseFramesAndExpectedRejectionHasNoStack() {
        try (var logs = new Capture(ConversationExceptionHandler.class)) {
            var request = new MockHttpServletRequest("POST", "/arte/ai-new/tool-chat/submitToolChat");
            request.addHeader("Authorization", "Bearer private-header");
            request.setContent("private-body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var handler = new ConversationExceptionHandler();
            var failure = new IllegalStateException("private-model", new java.sql.SQLException("private-sql", "08006", 11));
            assertEquals(500, handler.handle(failure, request, Locale.US).getStatusCode().value());
            var event = logs.find("New AI HTTP failure");
            assertEquals(Level.ERROR, event.getLevel());
            assertNotNull(event.getThrown());
            assertTrue(rendered(event).contains("java.sql.SQLException"));
            assertTrue(rendered(event).contains("sqlState=08006"));
            assertTrue(rendered(event).contains("FailureDiagnosticsTest.java"));
            assertFalse(rendered(event).contains("private-"));
            assertEquals(400, handler.handle(new IllegalArgumentException("private-invalid"), request, Locale.US).getStatusCode().value());
            assertNull(logs.find("New AI HTTP rejected").getThrown());
        }
    }

    private ToolChatCoordinator coordinator(ToolChatLoopTest.Rig r, ToolChatRunStore runs, ExecutionResultStore results) {
        return new ToolChatCoordinator(runs, r.f.executions, results, r.invocationCoordinator, r.toolAdmission, r.budgets,
                r.authorization, r.access, r.f.store, r.properties, r.f.clock, Duration.ofMillis(100));
    }

    @Test
    public void protocolRejectionIdentifiesTheExactRuleWithoutModelContent() throws Exception {
        try (var r = new ToolChatLoopTest.Rig(); var logs = new Capture(ToolChatCoordinator.class)) {
            ExecutionResultStore altered = new ExecutionResultStore() {
                public Mono<StoreOutcome<ResultRef>> put(com.arte.ainew.common.execution.ExecutionOwner owner, String id, String attempt, String key, InvocationResult result) {
                    return r.f.payloads.put(owner, id, attempt, key, result);
                }

                public Mono<InvocationResult> find(com.arte.ainew.common.execution.ExecutionOwner owner, String id, ResultRef ref) {
                    return r.f.payloads.find(owner, id, ref).map(value -> {
                        var original = ((InvocationResult.Generation) value).value();
                        return new InvocationResult.Generation(new ModelResult(original.resultId(), original.model(), original.outputs(), original.finishReason(), original.complete(),
                                new com.arte.core.pojo.value.StructuredValue.StringValue("private-model-content"), original.usage(), original.sources()));
                    });
                }
            };
            r.coordinator = coordinator(r, r.runs, altered);
            r.submit();
            r.until(run -> run.state().terminal());
            assertEquals("INVALID_MODEL_OUTPUT", r.run().error().code());
            var event = logs.find("protocol rejected");
            String message = rendered(event);
            for (var field : List.of("runId=run", "traceId=root-trace", "phase=WAITING_MODEL", "childIds=", "reason=STRUCTURED_OUTPUT_NOT_SUPPORTED"))
                assertTrue(message, message.contains(field));
            assertFalse(message.contains("private-"));
            assertNull(event.getThrown());
            assertEquals(0, r.f.invocations.get());
        }
    }

    @Test
    public void rootDeadlineLogsPriorPhaseAndOriginalDeadlineWithoutInventingAStack() throws Exception {
        try (var r = new ToolChatLoopTest.Rig(); var logs = new Capture(ToolChatCoordinator.class)) {
            var input = r.input();
            var shortRun = new ToolChatSubmission(input.conversationId(), input.expectedVersion(), input.text(), input.binding(), input.capability(), input.modelBudgetRef(),
                    input.maxInputTokens(), input.generation(), input.toolBindingIds(), new ToolChatRunSnapshot.Limits(Duration.ofSeconds(1), 3, 4, 1), input.budgetLimits());
            r.service.submit(shortRun, r.session, r.context).block(ToolChatLoopTest.WAIT);
            var originalDeadline = r.runs.snapshot(r.f.owner, "run").block(ToolChatLoopTest.WAIT).context().deadline();
            Thread.sleep(1100);
            r.tick();
            var event = logs.find("failure committed");
            String message = rendered(event);
            for (var field : List.of("code=RUN_DEADLINE_EXCEEDED", "traceId=root-trace", "previousPhase=READY_MODEL", "deadline=" + originalDeadline, "observedAt=", "remainingMs=-"))
                assertTrue(message, message.contains(field));
            assertNull(event.getThrown());
            assertEquals(ToolChatRun.State.TIMED_OUT, r.run().state());
            assertEquals(0, r.modelCalls.get());
        }
    }

    @Test
    public void workerUnexpectedFailureRetainsCauseFramesAndDoesNotFailOrRedispatchTheRun() throws Exception {
        try (var r = new ToolChatLoopTest.Rig(); var logs = new Capture(ToolChatWorker.class)) {
            r.submit();
            var failure = new IllegalStateException("private-request", new java.sql.SQLException("private-database-secret"));
            var store = (ToolChatRunStore) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ToolChatRunStore.class}, (proxy, method, args) -> {
                if (method.getName().equals("find")) return Mono.error(failure);
                try {
                    return method.invoke(r.runs, args);
                } catch (java.lang.reflect.InvocationTargetException error) {
                    throw error.getCause();
                }
            });
            var worker = new ToolChatWorker(store, coordinator(r, store, r.f.payloads), new NewAiToolChatProperties(true, false, null, null, 0), r.f.timer, false);
            assertEquals(Integer.valueOf(1), worker.pollOnce().block(ToolChatLoopTest.WAIT));
            var event = logs.find("checkpoint failed");
            assertEquals(Level.ERROR, event.getLevel());
            assertNotNull(event.getThrown());
            assertTrue(rendered(event).contains("runId=run"));
            assertTrue(rendered(event).contains("java.sql.SQLException"));
            assertFalse(rendered(event).contains("private-"));
            assertEquals(ToolChatRun.State.ACCEPTED, r.run().state());
            assertEquals(0, r.count("arte_ai_invocation"));
        }
    }

    @Test
    public void unexpectedToolAdapterFailureHasSafeStackAndKeepsUnknownOutcome() throws Exception {
        try (var r = new ToolChatLoopTest.Rig(); var logs = new Capture(com.arte.ainew.application.gateway.DefaultToolGateway.class)) {
            r.f.operation = call -> Mono.error(new IllegalStateException("private-tool-arguments", new java.sql.SQLException("private-sql-parameters")));
            r.submit();
            r.until(run -> run.state() == ToolChatRun.State.PENDING_RECONCILIATION);
            var event = logs.find("tool gateway failed");
            assertEquals(Level.ERROR, event.getLevel());
            assertNotNull(event.getThrown());
            assertTrue(rendered(event).contains("traceId=root-trace"));
            assertTrue(rendered(event).contains("adapterEntered=true"));
            assertFalse(rendered(event).contains("private-"));
            assertEquals(1, r.f.invocations.get());
        }
    }
}
