package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.config.NewAiSearchProperties;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class ExecutionSqlDiagnosticsTest {
    private final ArrayList<LogEvent> events = new ArrayList<>();
    private final Logger logger = (Logger) LogManager.getLogger(ExecutionSqlDiagnostics.class);
    private final AbstractAppender appender = new AbstractAppender("search-sql-test", null, null, false, Property.EMPTY_ARRAY) {
        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    };
    private Level previous;

    @Before
    public void capture() {
        previous = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @After
    public void cleanup() {
        logger.removeAppender(appender);
        logger.setLevel(previous);
        appender.stop();
    }

    private Object execute(ExecutionSqlDiagnostics diagnostics, SQLException failure) throws Throwable {
        var configuration = new Configuration();
        var statement = new MappedStatement.Builder(configuration,
                "com.arte.ainew.persistence.mybatis.mapper.SearchSyncMapper.register",
                new StaticSqlSource(configuration, "UPDATE diagnostic_test SET snapshot=?"), SqlCommandType.UPDATE).build();
        var target = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Executor.class}, (p, method, args) -> {
            if (failure != null) throw failure;
            return 1;
        });
        return diagnostics.intercept(new Invocation(target, Executor.class.getMethod("update", MappedStatement.class, Object.class),
                new Object[]{statement, Map.of("snapshot", "private-input-secret")}));
    }

    private ExecutionSqlDiagnostics background(boolean verbose, long elapsedMs, long thresholdMs) {
        var nanos = new AtomicLong();
        return new ExecutionSqlDiagnostics(new NewAiSearchProperties.Diagnostics(verbose, Duration.ofMillis(thresholdMs)),
                () -> nanos.getAndAdd(Duration.ofMillis(elapsedMs).toNanos()));
    }

    @Test
    public void backgroundSuccessIsSilentByDefault() throws Throwable {
        assertEquals(1, execute(background(false, 40, 500), null));
        assertTrue(events.isEmpty());
    }

    @Test
    public void enabledBackgroundDetailsKeepBothDebugEventsWithoutParameters() throws Throwable {
        assertEquals(1, execute(background(true, 40, 500), null));
        assertEquals(2, events.size());
        assertTrue(events.getFirst().getMessage().getFormattedMessage().startsWith("New AI SQL started"));
        assertTrue(events.getLast().getMessage().getFormattedMessage().contains("elapsedMs=40"));
        assertTrue(events.stream().allMatch(e -> e.getLevel() == Level.DEBUG));
        assertFalse(events.toString().contains("private-input-secret"));
    }

    @Test
    public void mutedBackgroundStillWarnsAtConfiguredSlowThreshold() throws Throwable {
        logger.setLevel(Level.WARN);
        assertEquals(1, execute(background(false, 100, 100), null));
        assertEquals(1, events.size());
        assertEquals(Level.WARN, events.getFirst().getLevel());
        String message = events.getFirst().getMessage().getFormattedMessage();
        assertTrue(message.contains("New AI background SQL slow"));
        assertTrue(message.contains("elapsedMs=100"));
        assertTrue(message.contains("snapshot=?"));
        assertFalse(message.contains("private-input-secret"));
    }

    @Test
    public void mutedBackgroundKeepsFailureDiagnosticsAndOriginalCause() {
        var failure = new SQLException("private-driver-secret", "42000", 123);
        var thrown = assertThrows(Throwable.class, () -> execute(background(false, 40, 500), failure));
        assertSame(failure, org.springframework.core.NestedExceptionUtils.getMostSpecificCause(thrown));
        assertEquals(1, events.size());
        assertEquals(Level.ERROR, events.getFirst().getLevel());
        String message = events.getFirst().getMessage().getFormattedMessage();
        assertTrue(message.contains("New AI SQL failed"));
        assertTrue(message.contains("sqlState=42000, vendorCode=123"));
        assertTrue(message.contains("snapshot=?"));
        assertFalse(message.contains("private-input-secret"));
        assertFalse(message.contains("private-driver-secret"));
    }

    @Test
    public void foregroundFactoryKeepsExistingDebugDiagnostics() throws Throwable {
        assertEquals(1, execute(new ExecutionSqlDiagnostics(), null));
        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(e -> e.getLevel() == Level.DEBUG));
    }

    @Test
    public void searchFactoryUsesItsOwnPolicyWithoutMutingForegroundFactory() throws Throwable {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:search-log-factory");
        var background = SearchSqlSessionFactory.create(ds, new NewAiSearchProperties.Diagnostics(false, Duration.ofMinutes(1)));
        execute((ExecutionSqlDiagnostics) background.getConfiguration().getInterceptors().getFirst(), null);
        assertTrue(events.isEmpty());
        var foreground = SearchSqlSessionFactory.create(ds);
        execute((ExecutionSqlDiagnostics) foreground.getConfiguration().getInterceptors().getFirst(), null);
        assertEquals(2, events.size());
    }
}
