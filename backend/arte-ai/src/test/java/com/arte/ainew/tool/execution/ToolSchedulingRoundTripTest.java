package com.arte.ainew.tool.execution;

import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.pojo.execution.StoreOutcome;
import org.junit.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.arte.ainew.pojo.execution.OutboxMessage.WorkClass.TOOL_SHORT;
import static com.arte.ainew.tool.execution.ToolSchedulingIntegrationTest.WAIT;
import static org.junit.Assert.*;

/**
 * P05 使用真实 JDBC 核对领取往返、整批游标提交及当前许可的防重约束。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 22:05 ✾
 */
public class ToolSchedulingRoundTripTest {
    private static final class SqlProbe extends DelegatingDataSource {
        final List<String> executions = new CopyOnWriteArrayList<>();

        SqlProbe(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            var connection = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                var result = invoke(method, connection, args);
                if (!method.getName().equals("prepareStatement")) return result;
                String sql = ((String) args[0]).replaceAll("\\s+", " ").trim();
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (statementProxy, statementMethod, statementArgs) -> {
                    if (statementMethod.getName().startsWith("execute")) executions.add(sql);
                    return invoke(statementMethod, result, statementArgs);
                });
            });
        }

        private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        }

        long matching(String prefix) {
            return executions.stream().filter(sql -> sql.startsWith(prefix)).count();
        }
    }

    private static MybatisExecutionPersistence observed(ToolSchedulingIntegrationTest.Rig rig, SqlProbe probe) {
        return new MybatisExecutionPersistence(probe, rig.f.codec, rig.f.io, () -> {
        }, () -> {
        }, rig.policy, true);
    }

    @Test
    public void warmEmptyQueueSkipsPermitsAndCursorWithoutSkippingPoolCheck() throws Exception {
        try (var r = new ToolSchedulingIntegrationTest.Rig()) {
            var probe = new SqlProbe(r.f.source);
            var store = observed(r, probe);
            assertTrue(store.claimClass(TOOL_SHORT, "probe", Duration.ofSeconds(3), 3, r.manifest).block(WAIT).isEmpty());
            probe.executions.clear();
            assertTrue(store.claimClass(TOOL_SHORT, "probe", Duration.ofSeconds(3), 3, r.manifest).block(WAIT).isEmpty());
            assertEquals(4, probe.executions.size());
            assertEquals(1, probe.matching("SELECT * FROM arte_ai_dispatch_pool"));
            assertTrue(probe.executions.stream().noneMatch(sql -> sql.contains("arte_ai_scheduling_permit") || sql.contains("arte_ai_dispatch_cursor")));
            r.f.jdbc.update("UPDATE arte_ai_dispatch_pool SET policy_digest=?", "0".repeat(64));
            assertThrows(com.arte.ainew.application.execution.DispatchSchedulingException.class,
                    () -> store.claimClass(TOOL_SHORT, "probe", Duration.ofSeconds(3), 3, r.manifest).block(WAIT));
        }
    }

    @Test
    public void batchClaimsThreeSpacesWithOneCursorReadAndOneWrite() throws Exception {
        try (var r = new ToolSchedulingIntegrationTest.Rig(8, 8, 8, 1000, 100, 20)) {
            for (String space : List.of("a", "b", "c")) {
                r.raw(space + "-1", space, "provider", TOOL_SHORT);
                r.raw(space + "-2", space, "provider", TOOL_SHORT);
            }
            var probe = new SqlProbe(r.f.source);
            var store = observed(r, probe);
            var messages = store.claimClass(TOOL_SHORT, "probe", Duration.ofSeconds(3), 3, r.manifest).block(WAIT);
            assertEquals(3, messages.size());
            assertEquals(3, messages.stream().map(message -> message.owner().workspaceId()).distinct().count());
            assertEquals(19, probe.executions.size());
            assertEquals(1, probe.matching("SELECT last_space FROM arte_ai_dispatch_cursor"));
            assertEquals(1, probe.matching("INSERT INTO arte_ai_dispatch_cursor"));
            assertEquals(3, r.f.count("arte_ai_scheduling_permit"));
            String lastSpace = r.f.jdbc.queryForObject("SELECT space_key FROM arte_ai_outbox WHERE message_key=?", String.class, messages.getLast().messageId());
            assertEquals(lastSpace, r.f.jdbc.queryForObject("SELECT last_space FROM arte_ai_dispatch_cursor", String.class));
            for (var message : messages) assertTrue(store.acknowledge(message).block(WAIT).successful());
            probe.executions.clear();
            assertEquals(3, store.claimClass(TOOL_SHORT, "probe", Duration.ofSeconds(3), 3, r.manifest).block(WAIT).size());
            assertEquals(1, probe.matching("SELECT last_space FROM arte_ai_dispatch_cursor"));
            assertEquals(1, probe.matching("UPDATE arte_ai_dispatch_cursor"));
        }
    }

    @Test
    public void failedFinalCursorWriteRollsBackEveryClaimAndPermit() throws Exception {
        try (var r = new ToolSchedulingIntegrationTest.Rig(8, 8, 8, 1000, 100, 20)) {
            r.raw("one", "a", "provider", TOOL_SHORT);
            r.raw("two", "b", "provider", TOOL_SHORT);
            r.f.jdbc.execute("ALTER TABLE arte_ai_dispatch_cursor ADD CONSTRAINT fail_cursor CHECK(last_space='forbidden')");
            assertThrows(Exception.class, () -> r.claim(TOOL_SHORT, 2));
            assertEquals(0, r.f.count("arte_ai_scheduling_permit"));
            assertEquals(0, r.f.count("arte_ai_dispatch_cursor"));
            assertEquals(0, r.number("SELECT SUM(token) FROM arte_ai_outbox WHERE kind='DISPATCH'"));
            assertEquals(0, r.number("SELECT COUNT(*) FROM arte_ai_outbox WHERE worker_id IS NOT NULL"));
            r.f.jdbc.execute("ALTER TABLE arte_ai_dispatch_cursor DROP CONSTRAINT fail_cursor");
            assertEquals(2, r.claim(TOOL_SHORT, 2).size());
        }
    }

    @Test
    public void currentPermitLookupRejectsWrongTokenClassExpiryAndMissingRow() throws Exception {
        try (var r = new ToolSchedulingIntegrationTest.Rig()) {
            r.raw("one", "a", "provider", TOOL_SHORT);
            OutboxMessage message = r.claim(TOOL_SHORT, 1).getFirst();
            var probe = new SqlProbe(r.f.source);
            var store = observed(r, probe);
            assertTrue(store.validateClaim(message).block(WAIT).successful());
            assertEquals(4, probe.executions.size());
            assertEquals(1, probe.matching("SELECT * FROM arte_ai_scheduling_permit WHERE message_key="));
            assertTrue(probe.executions.stream().noneMatch(sql -> sql.startsWith("SELECT * FROM arte_ai_outbox WHERE message_key=") && !sql.endsWith("FOR UPDATE")));
            var forged = new OutboxMessage(message.messageId().toUpperCase(java.util.Locale.ROOT), message.invocationId(), message.owner(),
                    message.kind(), message.eventSequence(), message.workerId(), message.fencingToken(), message.leaseExpiresAt(), message.workClass());
            assertFalse(store.validateClaim(forged).block(WAIT).successful());
            r.f.jdbc.update("UPDATE arte_ai_scheduling_permit SET work_class='tool_short'");
            assertEquals(StoreOutcome.Code.LEASE_LOST, store.validateClaim(message).block(WAIT).code());
            r.f.jdbc.update("UPDATE arte_ai_scheduling_permit SET work_class='TOOL_SHORT'");
            r.f.jdbc.update("UPDATE arte_ai_scheduling_permit SET token=token+1");
            assertEquals(StoreOutcome.Code.LEASE_LOST, store.validateClaim(message).block(WAIT).code());
            r.f.jdbc.update("UPDATE arte_ai_scheduling_permit SET token=?,work_class='TOOL_LONG'", message.fencingToken());
            assertEquals(StoreOutcome.Code.LEASE_LOST, store.validateClaim(message).block(WAIT).code());
            r.f.jdbc.update("UPDATE arte_ai_scheduling_permit SET work_class='TOOL_SHORT',lease_until=0");
            assertEquals(StoreOutcome.Code.LEASE_LOST, store.validateClaim(message).block(WAIT).code());
            r.f.jdbc.update("DELETE FROM arte_ai_scheduling_permit");
            assertEquals(StoreOutcome.Code.LEASE_LOST, store.validateClaim(message).block(WAIT).code());
        }
    }
}
