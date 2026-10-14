package com.arte.app.platform.persistence;

import com.alibaba.druid.pool.DruidDataSource;
import com.arte.app.platform.identity.*;
import com.arte.core.application.workspace.DefaultWorkspacePolicies;
import com.arte.core.application.workspace.DefaultWorkspaceServices;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.Scope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.scheduler.Schedulers;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in, isolated service-level baseline; never runs against the application's database.
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 11:55 ✾
 */
@EnabledIfSystemProperty(named = "platform.workspace.baseline", matches = "true")
class WorkspaceDirectoryBaselineTest {
    private final ThreadLocal<Sample> active = new ThreadLocal<>();

    @Test
    void recordDirectoryBaseline() throws Exception {
        assertNotNull(System.getProperty("platform.test.mysql"), "A dedicated test MySQL is required");
        String redisAddress = Objects.requireNonNull(System.getProperty("platform.test.redis"));
        String prefix = "arte:workspace-baseline:" + UUID.randomUUID() + ":";
        var fixture = new PlatformPersistenceIntegrationTest();
        RedissonClient redis = null;
        try {
            fixture.setup();
            Scope base = fixture.initialize().value().workspace().scope();
            var redisConfig = new Config();
            redisConfig.useSingleServer().setAddress(redisAddress).setConnectionMinimumIdleSize(1)
                    .setConnectionPoolSize(8).setSubscriptionConnectionMinimumIdleSize(1);
            redis = Redisson.create(redisConfig);
            int workers = Integer.getInteger("platform.workspace.baseline.workers", 8);
            int requests = Integer.getInteger("platform.workspace.baseline.requests", 32);
            assertTrue(workers > 0 && workers <= 128 && requests >= 32 && requests <= 1000);
            var scheduler = Schedulers.newBoundedElastic(workers, 128, "workspace-baseline");
            try (var pool = new DruidDataSource()) {
                pool.setUrl(fixture.source.getUrl());
                pool.setUsername("root");
                pool.setPassword("platform-test");
                pool.setInitialSize(workers);
                pool.setMaxActive(workers);
                pool.setMinIdle(workers);
                pool.setMaxWait(5000);
                pool.init();
                var source = counted(pool);
                var tx = new JdbcPublicTransactionRunner(source, new DataSourceTransactionManager(source), 15);
                var accounts = new RbacAccountReader(source, new RbacAccountMapperConfiguration().identityUserMapper(source));
                var provider = new RbacIdentityProvider(accounts, new JdbcPrincipalIdentityStore(source, accounts),
                        new TransactionTemplate(new DataSourceTransactionManager(source)),
                        new PlatformIdentityProperties(true, 0L, List.of()), List::of, Clock.systemUTC());
                var delegate = new RedisWorkspaceCursorStore(redis, fixture.json, Duration.ofMinutes(10), prefix);
                var cursors = new WorkspaceCursorStore() {
                    public String save(Cursor cursor) {
                        if (active.get() != null) active.get().redisWrites++;
                        return delegate.save(cursor);
                    }

                    public Cursor load(String token) {
                        if (active.get() != null) active.get().redisReads++;
                        return delegate.load(token);
                    }
                };
                var factory = new PlatformServiceTestFactory(source, tx, fixture.json, cursors);
                var services = new DefaultWorkspaceServices(provider, tx, factory.tenants(), factory.workspaces(),
                        factory.tenantMembers(), factory.workspaceMembers(), factory.policies(), factory.preferences(),
                        factory.receipts(), factory.outbox(), factory.audit(), () -> UUID.randomUUID().toString(),
                        Clock.systemUTC(), new DefaultWorkspacePolicies(factory.templates()), factory.facts());
                var executor = new PlatformPersistenceExecutor(scheduler, tx);
                var browser = new com.arte.app.platform.workspace.WorkspaceDirectoryBrowser(services.workspaces(), cursors);
                String mode = System.getProperty("platform.workspace.baseline.mode", "legacy");
                assertTrue(Set.of("legacy", "browse").contains(mode));
                var output = new ArrayList<Map<String, Object>>();
                for (int count : Arrays.stream(System.getProperty("platform.workspace.baseline.sizes", "100,1000,10000").split(","))
                        .mapToInt(Integer::parseInt).toArray()) {
                    assertTrue(count >= 100 && count <= 100000 && count % 100 == 0);
                    seed(fixture, base, count);
                    for (int visiblePercent : new int[]{100, 10}) {
                        fixture.jdbc.update("UPDATE arte_platform_workspace_member SET state=CASE WHEN MOD(CAST(SUBSTRING(workspace_id,6) AS UNSIGNED),100)<? THEN 0 ELSE 1 END WHERE workspace_id LIKE 'bench%'", visiblePercent);
                        for (int concurrency : new int[]{1, 8, 32}) {
                            // Warm both the workers and the query/connection paths; exclude warm-up samples.
                            for (int i = 0; i < 2; i++)
                                executor.read(() -> mode.equals("browse")
                                        ? browser.browse(fixture.actor, null, new com.arte.core.pojo.workspace.WorkspaceBrowseQuery(20, ""))
                                        : services.workspaces().listAccessible(fixture.actor, new PageQuery(1, 20))).block();
                            var samples = new ArrayList<Sample>();
                            for (int offset = 0; offset < requests; offset += concurrency) {
                                var pending = new ArrayList<CompletableFuture<Sample>>();
                                for (int i = 0; i < Math.min(concurrency, requests - offset); i++) {
                                    var sample = new Sample();
                                    sample.submitted = System.nanoTime();
                                    pending.add(executor.read(() -> {
                                        long started = System.nanoTime();
                                        sample.queueMs = millis(started - sample.submitted);
                                        long allocated = allocatedBytes();
                                        active.set(sample);
                                        try {
                                            if (mode.equals("browse")) {
                                                var page = browser.browse(fixture.actor, null, new com.arte.core.pojo.workspace.WorkspaceBrowseQuery(20, ""));
                                                assertTrue(page.records().size() <= Math.min(20, 1L + count * visiblePercent / 100));
                                                assertTrue(sample.candidateBatches <= 4);
                                                assertTrue(sample.candidateRowsFetched <= 204);
                                                assertTrue(page.exhausted() || page.nextCursor() != null);
                                                sample.returnedRecords = page.records().size();
                                            } else {
                                                var page = services.workspaces().listAccessible(fixture.actor, new PageQuery(1, 20));
                                                assertEquals(1L + count * visiblePercent / 100, page.total());
                                                assertEquals(Math.min(20, page.total()), page.records().size());
                                                sample.returnedRecords = page.records().size();
                                            }
                                            sample.totalMs = millis(System.nanoTime() - sample.submitted);
                                            sample.allocatedBytes = allocated < 0 ? -1 : allocatedBytes() - allocated;
                                            return sample;
                                        } finally {
                                            active.remove();
                                        }
                                    }).toFuture());
                                }
                                for (var future : pending)
                                    samples.add(future.get(5, java.util.concurrent.TimeUnit.MINUTES));
                            }
                            var scenario = new LinkedHashMap<String, Object>();
                            scenario.put("candidates", count + 1);
                            scenario.put("visiblePercentOfSeeded", visiblePercent);
                            scenario.put("concurrency", concurrency);
                            scenario.put("samples", samples);
                            output.add(scenario);
                            System.out.printf("WORKSPACE_BASELINE candidates=%d visible=%d%% concurrency=%d medianMs=%.3f sql=%d redis=%d%n",
                                    count + 1, visiblePercent, concurrency,
                                    samples.stream().mapToDouble(s -> s.totalMs).sorted().toArray()[samples.size() / 2],
                                    samples.getFirst().sqlCount, samples.getFirst().redisReads + samples.getFirst().redisWrites);
                        }
                    }
                }
                var report = new LinkedHashMap<String, Object>();
                report.put("kind", "workspace-directory-service-baseline");
                report.put("mode", mode);
                report.put("browseBudget", Map.of("batchSize", 100, "maxCandidates", 200, "maxBatches", 4,
                        "maxWorkTimeMs", 1000, "initialBatch", "min(size*2,batchSize)"));
                report.put("observedAt", Instant.now().toString());
                report.put("javaVersion", System.getProperty("java.version"));
                report.put("sqlDebugLogging", false);
                report.put("mysqlVersion", fixture.jdbc.queryForObject("SELECT VERSION()", String.class));
                report.put("workers", workers);
                report.put("queueCapacityPerWorker", 128);
                report.put("connectionPoolSize", workers);
                report.put("requestsPerScenario", requests);
                report.put("page", Map.of("current", 1, "size", 20));
                report.put("scenarios", output);
                report.put("candidateExplain", fixture.jdbc.queryForList("EXPLAIN SELECT m.tenant_id,m.workspace_id,m.workspace_created_at FROM arte_platform_workspace_member m WHERE m.subject_digest=? AND m.subject_id=? ORDER BY m.workspace_created_at DESC,m.tenant_id,m.workspace_id LIMIT 101", digest(fixture.actor.principal().subjectId()), fixture.actor.principal().subjectId()));
                report.put("indexSizes", fixture.jdbc.queryForList("SELECT table_name,index_name,stat_value AS pages FROM mysql.innodb_index_stats WHERE database_name=? AND stat_name='size'", fixture.database));
                Path destination = Path.of(System.getProperty("platform.workspace.baseline.output", "target/workspace-directory-baseline.json"));
                Files.createDirectories(destination.toAbsolutePath().getParent());
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), report);
            } finally {
                scheduler.dispose();
            }
        } finally {
            if (redis != null) {
                try {
                    redis.getKeys().deleteByPattern(prefix + "*");
                } finally {
                    redis.shutdown();
                }
            }
            if (fixture.database != null) fixture.cleanup();
        }
    }

    private static void seed(PlatformPersistenceIntegrationTest f, Scope base, int count) throws Exception {
        f.jdbc.update("DELETE FROM arte_platform_workspace_policy WHERE workspace_id LIKE 'bench%'");
        f.jdbc.update("DELETE FROM arte_platform_workspace_member WHERE workspace_id LIKE 'bench%'");
        f.jdbc.update("DELETE FROM arte_platform_workspace WHERE id LIKE 'bench%'");
        var rows = new ArrayList<Object[]>();
        var members = new ArrayList<Object[]>();
        var policies = new ArrayList<Object[]>();
        String subject = f.actor.principal().subjectId();
        for (int i = 0; i < count; i++) {
            String id = "bench%08d".formatted(i);
            long sec = 1_797_000_000L - i / 100;
            rows.add(new Object[]{base.tenantId(), id, id, "", 0, subject, 0, 0, java.time.LocalDateTime.ofInstant(Instant.ofEpochSecond(sec, 123456000), java.time.ZoneOffset.UTC), java.time.LocalDateTime.ofInstant(Instant.ofEpochSecond(sec, 123456000), java.time.ZoneOffset.UTC)});
            members.add(new Object[]{id, subject, digest(subject), base.tenantId(), id});
            policies.add(new Object[]{id, base.tenantId(), base.workspaceId()});
        }
        f.jdbc.batchUpdate("INSERT INTO arte_platform_workspace(tenant_id,id,name,description,state,created_by,version,auth_epoch,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)", rows);
        f.jdbc.batchUpdate("INSERT INTO arte_platform_workspace_member(id,tenant_id,workspace_id,subject_id,subject_digest,workspace_created_at,role,state,generation,version) SELECT ?,tenant_id,id,?,?,created_at,2,0,0,0 FROM arte_platform_workspace WHERE tenant_id=? AND id=?", members);
        f.jdbc.batchUpdate("INSERT INTO arte_platform_workspace_policy(tenant_id,workspace_id,rules,policy_schema_version,role_preset_version,version) SELECT tenant_id,?,rules,policy_schema_version,role_preset_version,0 FROM arte_platform_workspace_policy WHERE tenant_id=? AND workspace_id=?", policies);
        f.jdbc.execute("ANALYZE TABLE arte_platform_workspace, arte_platform_workspace_member");
    }

    private DelegatingDataSource counted(DruidDataSource pool) {
        return new DelegatingDataSource(pool) {
            @Override
            public Connection getConnection() throws SQLException {
                long start = System.nanoTime();
                Connection connection = super.getConnection();
                if (active.get() != null) active.get().connectionMs += millis(System.nanoTime() - start);
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
                    Object value = invoke(connection, method, args);
                    if (value instanceof PreparedStatement statement) return statement(statement,
                            args[0].toString().contains("FROM arte_platform_workspace_member m"));
                    return value;
                });
            }
        };
    }

    private PreparedStatement statement(PreparedStatement delegate, boolean candidateQuery) {
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{PreparedStatement.class}, (proxy, method, args) -> {
            boolean execute = Set.of("execute", "executeQuery", "executeUpdate", "executeBatch").contains(method.getName());
            long start = System.nanoTime();
            Object result;
            try {
                result = invoke(delegate, method, args);
            } finally {
                if (execute && active.get() != null) {
                    active.get().sqlCount++;
                    active.get().sqlMs += millis(System.nanoTime() - start);
                    if (candidateQuery) active.get().candidateBatches++;
                }
            }
            if (candidateQuery && result instanceof ResultSet rows)
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{ResultSet.class}, (p, m, a) -> {
                    Object value = invoke(rows, m, a);
                    if (m.getName().equals("next") && Boolean.TRUE.equals(value) && active.get() != null)
                        active.get().candidateRowsFetched++;
                    return value;
                });
            return result;
        });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static String digest(String subject) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(subject.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static long allocatedBytes() {
        var bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocation) || !allocation.isThreadAllocatedMemorySupported())
            return -1;
        if (!allocation.isThreadAllocatedMemoryEnabled()) allocation.setThreadAllocatedMemoryEnabled(true);
        return allocation.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    public static final class Sample {
        public long submitted;
        public double totalMs, queueMs, sqlMs, connectionMs;
        public long sqlCount, redisReads, redisWrites, candidateBatches, candidateRowsFetched, allocatedBytes, returnedRecords;
    }
}
