package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.pojo.search.SearchSync.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * 用受控的旧 SQL 时钟采样和单调耗时模拟锁等待，不用 H2 的时钟行为冒充 MySQL。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 10:20 ✾
 */
public class SearchSyncLeaseClockTest {
    private static final String G = "clock-test-g01";
    private ClockDataSource dataSource;
    private JdbcTemplate jdbc;
    private MybatisConversationSearchSyncStore store;
    private Lease lease;

    @Before
    public void setup() {
        var raw = new JdbcDataSource();
        raw.setURL("jdbc:h2:mem:lease-clock-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql"),
                new ClassPathResource("arte-ai-new-search-sync-migration.sql")).execute(raw);
        jdbc = new JdbcTemplate(raw);
        dataSource = new ClockDataSource(raw);
        var nanos = new AtomicLong();
        store = new MybatisConversationSearchSyncStore(dataSource, 10, new NewAiSearchProperties.Diagnostics(null, null),
                () -> nanos.getAndAdd(Duration.ofSeconds(2).toNanos()));
        store.register(G, "v2");
        lease = store.acquire(G, "owner", Duration.ofSeconds(60));
        dataSource.sql.clear();
    }

    @After
    public void cleanup() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    public void expiredWhileWaitingCannotBeRevivedByOldStatementTimestamp() {
        long now = store.now();
        jdbc.update("UPDATE arte_ai_search_generation SET lease_until=? WHERE generation=?", now - 100, G);
        dataSource.sample.set(now - 1000);
        dataSource.sql.clear();
        assertThrows(LostLease.class, () -> store.renew(lease, Duration.ofSeconds(60)));
        assertEquals(1, standaloneClocks());
        assertTrue(dataSource.sql.stream().noneMatch(sql -> sql.startsWith("UPDATE")));
        assertEquals(Long.valueOf(now - 100), jdbc.queryForObject("SELECT lease_until FROM arte_ai_search_generation WHERE generation=?", Long.class, G));
    }

    @Test
    public void conservativeAgeTriggersFreshReadWithoutRejectingAStillValidLease() {
        long now = store.now();
        jdbc.update("UPDATE arte_ai_search_generation SET lease_until=? WHERE generation=?", now + 1000, G);
        dataSource.sample.set(now - 500);
        dataSource.sql.clear();
        assertEquals(4, store.checkpoints(lease).size());
        assertEquals(1, standaloneClocks());
    }

    @Test
    public void normalGuardNeedsOneLockReadAndNoStandaloneClockQuery() {
        assertEquals(4, store.checkpoints(lease).size());
        assertEquals(2, dataSource.sql.size());
        assertEquals(0, standaloneClocks());
        assertTrue(dataSource.sql.getFirst().contains("AS database_now"));
        assertTrue(dataSource.sql.getFirst().endsWith("FOR UPDATE"));
    }

    @Test
    public void renewalDeadlineUsesTheWriteClockInsteadOfAnOldReadSample() {
        long before = store.now();
        dataSource.sample.set(before - 10000);
        store.renew(lease, Duration.ofSeconds(60));
        long until = jdbc.queryForObject("SELECT lease_until FROM arte_ai_search_generation WHERE generation=?", Long.class, G);
        assertTrue("Renewal must use the current write statement's database clock", until >= before + 60000);
    }

    @Test
    public void retryContinueAndSuccessTimesComeFromTheirWriteStatements() {
        var unit = new Unit(Kind.C, "a".repeat(64));
        store.discovered(lease, "C", List.of(unit), unit.key(), false, Duration.ZERO);
        var work = store.pending(lease, 1).getFirst();
        long before = store.now();
        dataSource.sample.set(before - 10000);
        store.fail(lease, work, "TEST_RETRY", Duration.ofSeconds(1));
        long retry = jdbc.queryForObject("SELECT next_at FROM arte_ai_search_work WHERE generation=?", Long.class, G);
        assertTrue(retry >= before + 1000);
        assertTrue(store.pending(lease, 1).isEmpty());
        var schedule = store.schedule(lease);
        assertEquals(Long.valueOf(retry), schedule.nextWorkAt());
        assertTrue(schedule.databaseNow() >= before);
        before = store.now();
        store.continueWork(lease, work, Duration.ofSeconds(2));
        assertTrue(jdbc.queryForObject("SELECT next_at FROM arte_ai_search_work WHERE generation=?", Long.class, G) >= before + 2000);
        before = store.now();
        store.complete(lease, work, null);
        assertTrue(jdbc.queryForObject("SELECT last_success_at FROM arte_ai_search_generation WHERE generation=?", Long.class, G) >= before);
        assertNull(store.schedule(lease).nextWorkAt());
    }

    @Test
    public void mergedGuardStillRejectsWrongWorkerAndFence() {
        assertThrows(LostLease.class, () -> store.checkpoints(new Lease(G, "other", lease.fence())));
        assertThrows(LostLease.class, () -> store.checkpoints(new Lease(G, lease.worker(), lease.fence() - 1)));
    }

    @Test
    public void leaseExpiringDuringEnqueueStillRollsBackTheReconciliationCheckpoint() {
        var immediate = new MybatisConversationSearchSyncStore(dataSource, 10, new NewAiSearchProperties.Diagnostics(null, null), () -> 0);
        long now = store.now();
        jdbc.update("UPDATE arte_ai_search_generation SET lease_until=? WHERE generation=?", now - 100, G);
        dataSource.sample.set(now - 1000);
        dataSource.afterEnqueue = () -> dataSource.sample.set(now);
        var work = new Work(new Unit(Kind.C, "b".repeat(64)), null, false, 0);
        assertThrows(LostLease.class, () -> immediate.reconcileDiscovered(lease, List.of(work), work.unit().taskKey(), true, Duration.ofSeconds(1)));
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_search_work", Long.class));
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT rounds FROM arte_ai_search_checkpoint WHERE generation=? AND lane='E'", Long.class, G));
    }

    private long standaloneClocks() {
        return dataSource.sql.stream().filter(sql -> sql.startsWith("SELECT CAST(")).count();
    }

    private static Object call(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static final class ClockDataSource extends DelegatingDataSource {
        final AtomicLong sample = new AtomicLong(Long.MIN_VALUE);
        final List<String> sql = new CopyOnWriteArrayList<>();
        Runnable afterEnqueue = () -> {
        };

        ClockDataSource(JdbcDataSource raw) {
            super(raw);
        }

        @Override
        public Connection getConnection() throws SQLException {
            var target = super.getConnection();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p, method, args) -> {
                var result = call(method, target, args);
                if (method.getName().equals("prepareStatement")) {
                    String statement = ((String) args[0]).replaceAll("\\s+", " ").trim();
                    sql.add(statement);
                    if (statement.startsWith("INSERT IGNORE INTO arte_ai_search_work")) afterEnqueue.run();
                    if (statement.contains("AS database_now") && statement.endsWith("FOR UPDATE"))
                        return statement((PreparedStatement) result);
                }
                return result;
            });
        }

        private PreparedStatement statement(PreparedStatement target) {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, method, args) -> {
                var result = call(method, target, args);
                if (result instanceof ResultSet rows)
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class}, (rp, rm, ra) -> {
                        var value = call(rm, rows, ra);
                        if (rm.getName().equals("getLong") && ra[0].toString().equalsIgnoreCase("database_now") && sample.get() != Long.MIN_VALUE)
                            return sample.get();
                        return value;
                    });
                return result;
            });
        }
    }
}
