package com.arte.app.diagnostics;

import com.alibaba.druid.pool.DruidDataSource;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.context.ExecutionContextRequest;
import com.arte.core.constant.CoreConstant;
import com.arte.core.diagnostics.ObservedScheduler;
import com.arte.core.diagnostics.RequestObservation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

public class NewAiRequestObservationTest {
    DruidDataSource dataSource;
    Scheduler scheduler;
    MockMvc mvc;
    Probe controller;

    @BeforeEach
    public void setup() throws Exception {
        RequestObservation.installReactorPropagation();
        dataSource = new DruidDataSource();
        dataSource.setUrl("jdbc:h2:mem:metrics-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setInitialSize(1);
        dataSource.setMaxActive(2);
        dataSource.setProxyFilters(List.of(new NewAiJdbcObservationFilter()));
        dataSource.init();
        scheduler = new ObservedScheduler(Schedulers.newBoundedElastic(1, 16, "metrics-db"), "db");
        controller = new Probe(new JdbcTemplate(dataSource), scheduler);
        mvc = standaloneSetup(controller).addFilters(new NewAiHttpObservationFilter()).build();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("same-account", null, List.of()));
    }

    @AfterEach
    public void cleanup() {
        RequestObservation.removeReactorPropagation();
        SecurityContextHolder.clearContext();
        scheduler.dispose();
        dataSource.close();
    }

    @Test
    public void capturesInitialFactoryAuthorizationSqlQueueConnectionAndTraceAcrossTimerAndAsyncDispatch() throws Exception {
        var result = mvc.perform(post("/ai-new/probe").header("X-Arte-Measurement-Id", "single-1")).andReturn();
        String trace = result.getResponse().getHeader("X-Arte-Trace-Id");
        var metric = (RequestObservation) result.getRequest().getAttribute(NewAiHttpObservationFilter.ATTRIBUTE);
        result.getAsyncResult(5000);
        var response = mvc.perform(asyncDispatch(result)).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals(trace, response.getContentAsString());
        var report = metric.report();
        assertEquals(1L, report.get("initialAuthorizationCount"));
        assertTrue((Double) report.get("initialAuthorizationMs") >= 35);
        assertEquals(2L, report.get("sqlCount"));
        assertEquals(2L, report.get("connectionCount"));
        assertEquals(32L, report.get("responseBytes"));
        assertTrue((Double) report.get("mvcTotalMs") >= (Double) report.get("initialAuthorizationMs"));
        assertTrue((Double) report.get("serverFirstWriteMs") >= (Double) report.get("initialAuthorizationMs"));
        assertEquals(List.of(trace, trace), controller.traces);
        assertTrue(controller.noAuthenticationOnDb.get());
        assertNull(RequestObservation.current());
        assertNull(metric.finish(200)); // Redispatch cannot emit a second request summary.
    }

    @Test
    public void measuresQueueFromSubmissionAndClearsContextForBackgroundTasks() throws Exception {
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        scheduler.schedule(() -> {
            holding.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(holding.await(1, TimeUnit.SECONDS));
        var metric = new RequestObservation("POST", "/ai-new/probe", null);
        var ran = new CountDownLatch(1);
        try (var ignored = RequestObservation.bind(metric)) {
            scheduler.schedule(ran::countDown);
        }
        Thread.sleep(55);
        release.countDown();
        assertTrue(ran.await(2, TimeUnit.SECONDS));
        var done = new CountDownLatch(1);
        scheduler.schedule(() -> {
            assertNull(RequestObservation.current());
            assertNull(MDC.get(CoreConstant.LOG_TRACE_ID));
            done.countDown();
        });
        assertTrue(done.await(2, TimeUnit.SECONDS));
        var report = metric.finish(200);
        var queues = (Map<?, ?>) report.get("schedulerQueues");
        var queue = (Map<?, ?>) queues.get("db");
        assertEquals(1L, queue.get("tasks"));
        assertTrue((Double) queue.get("maxMs") >= 50);
    }

    @Test
    public void countsFailedJdbcExecutionsAndDoesNotRecordParameters() {
        var metric = new RequestObservation("POST", "/ai-new/probe", "unsafe\nheader");
        try (var ignored = RequestObservation.bind(metric)) {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> new JdbcTemplate(dataSource).queryForObject("SELECT CAST(? AS INTEGER)", Integer.class, "private-secret"));
        }
        var report = metric.finish(500);
        assertEquals(1L, report.get("sqlCount"));
        assertEquals(1L, report.get("sqlErrors"));
        assertNull(report.get("measurementId"));
        assertFalse(report.toString().contains("private-secret"));
    }

    @Test
    public void extractsNativeMysqlIdThroughDruidStatementAndConnectionWrappers() throws Exception {
        // 驱动为 runtime 依赖；测试也使用反射，保持生产模块不依赖驱动编译类型。
        var nativeConnection = (java.sql.Connection) org.mockito.Mockito.mock(
                Class.forName("com.mysql.cj.jdbc.ConnectionImpl"), invocation ->
                        invocation.getMethod().getName().equals("getId") ? 42L
                                : org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation));
        var proxy = org.mockito.Mockito.mock(com.alibaba.druid.proxy.jdbc.ConnectionProxy.class);
        org.mockito.Mockito.when(proxy.getRawObject()).thenReturn(nativeConnection);
        var wrapped = new com.alibaba.druid.pool.DruidStatementConnection(proxy, null);
        assertEquals(42L, NewAiJdbcObservationFilter.mysqlId(wrapped));
        assertNull(NewAiJdbcObservationFilter.mysqlId(org.mockito.Mockito.mock(java.sql.Connection.class)));
    }

    @Test
    public void cancelledRequestReportsItsInFlightSqlWithoutPretendingItHasCompleted() {
        var metric = new RequestObservation("POST", "/ai-new/probe", null);
        assertTrue(metric.sqlStarted());
        var report = metric.finish(504);
        assertEquals(1L, report.get("sqlCount"));
        assertEquals(0L, report.get("sqlCompletedCount"));
        assertEquals(1L, report.get("sqlInFlight"));
        metric.sql(1_000_000, false, false, null);
        assertEquals(1L, report.get("sqlInFlight"));
    }

    @Test
    public void streamSubscribedAfterControllerReturnKeepsItsRequestTraceAndJdbcCounters() throws Exception {
        var result = mvc.perform(post("/ai-new/stream")).andReturn();
        String trace = result.getResponse().getHeader("X-Arte-Trace-Id");
        var metric = (RequestObservation) result.getRequest().getAttribute(NewAiHttpObservationFilter.ATTRIBUTE);
        result.getAsyncResult(5000);
        var streamed = mvc.perform(asyncDispatch(result)).andReturn();
        if (streamed.getRequest().isAsyncStarted()) {
            streamed.getAsyncResult(5000);
            streamed = mvc.perform(asyncDispatch(streamed)).andReturn();
        }
        var response = streamed.getResponse();
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains(trace));
        assertEquals(3L, metric.report().get("sqlCount"));
        assertEquals(List.of(trace, trace, trace), controller.traces);
        assertTrue(controller.noAuthenticationOnDb.get());
    }

    @RestController
    static class Probe {
        final ExecutionContextFactory factory;
        final JdbcTemplate jdbc;
        final Scheduler scheduler;
        final List<String> traces = new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicBoolean noAuthenticationOnDb = new java.util.concurrent.atomic.AtomicBoolean(true);

        Probe(JdbcTemplate jdbc, Scheduler scheduler) {
            this.jdbc = jdbc;
            this.scheduler = scheduler;
            factory = new ExecutionContextFactory((name, tenant, workspace, scopes) -> Mono.fromCallable(() -> {
                read();
                Thread.sleep(40);
                return new ExecutionAuthorization(new ExecutionPrincipal("subject", name, ExecutionPrincipal.Kind.USER), tenant, workspace, scopes, "grant");
            }).subscribeOn(scheduler), Clock.systemUTC());
        }

        void read() {
            traces.add(MDC.get(CoreConstant.LOG_TRACE_ID));
            noAuthenticationOnDb.compareAndSet(true, SecurityContextHolder.getContext().getAuthentication() == null);
            jdbc.queryForObject("SELECT 1", Integer.class);
        }

        @PostMapping(value = "/ai-new/probe", produces = MediaType.TEXT_PLAIN_VALUE)
        Mono<String> probe() {
            var request = new ExecutionContextRequest("tenant", "workspace", Set.of("read"), Duration.ofSeconds(10), RequestObservation.currentTraceId(), null, null, null);
            return RequestObservation.observe(factory.createCurrent(request).flatMap(context -> Mono.delay(Duration.ofMillis(25))
                    .flatMap(ignored -> Mono.fromCallable(() -> {
                        read();
                        return context.traceId();
                    }).subscribeOn(scheduler))));
        }

        @PostMapping(value = "/ai-new/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        Mono<org.springframework.http.ResponseEntity<reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<String>>>> stream() {
            var request = new ExecutionContextRequest("tenant", "workspace", Set.of("read"), Duration.ofSeconds(10), RequestObservation.currentTraceId(), null, null, null);
            return RequestObservation.observe(factory.createCurrent(request).map(context -> {
                var body = reactor.core.publisher.Flux.range(1, 2).delayElements(Duration.ofMillis(25))
                        .concatMap(ignored -> Mono.fromCallable(() -> {
                            read();
                            return org.springframework.http.codec.ServerSentEvent.builder(context.traceId()).build();
                        }).subscribeOn(scheduler));
                return org.springframework.http.ResponseEntity.ok().body(RequestObservation.observe(body));
            }));
        }
    }
}
