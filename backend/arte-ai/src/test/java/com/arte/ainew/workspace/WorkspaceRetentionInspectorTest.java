package com.arte.ainew.workspace;

import com.arte.ainew.application.workspace.JdbcWorkspaceRetentionInspector;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.BudgetReservation;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.execution.ExecutionOptions;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationRequest;
import com.arte.ainew.pojo.tool.ToolChatBudget;
import com.arte.ainew.pojo.tool.ToolChatRun;
import com.arte.ainew.pojo.tool.ToolInvocation;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.core.pojo.platform.ExecutionOwner;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.value.StructuredValue;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.Assert.*;

/**
 * 使用真实快照编码验证未知执行和资金预留，不对账、不篡改 owner。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/10 18:40 ✾
 */
public class WorkspaceRetentionInspectorTest {

    JdbcDataSource source;
    JdbcTemplate jdbc;
    JacksonExecutionRecordCodec codec = new JacksonExecutionRecordCodec();
    Scope scope = new Scope("tenant", "space");
    Instant at = Instant.parse("2026-11-10T00:00:00Z");

    @Before
    public void setup() {
        source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        for (String table : List.of("arte_ai_invocation", "arte_ai_account", "arte_ai_reservation"))
            jdbc.execute("CREATE TABLE " + table + "(id_key VARCHAR(64) PRIMARY KEY,workspace_snapshot CLOB)");
    }

    @After
    public void close() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    void write(String table, String id, Object value) {
        jdbc.update("MERGE INTO " + table + " KEY(id_key) VALUES(?,?)", id, codec.encode(value));
    }

    Money money(String value) {
        return new Money(new BigDecimal(value), Currency.getInstance("CNY"));
    }

    Invocation invocation(String workspace, Invocation.State state) {
        var auth = new ExecutionAuthorization(new ExecutionPrincipal("user", "user", ExecutionPrincipal.Kind.USER), "tenant", workspace, Set.of("ai:invoke"), "grant");
        var context = new ExecutionContext("invocation", "trace", auth, at.plusSeconds(90), null, null, "release", "key");
        var input = new ToolInvocation("call", new DefinitionRef("tool", "local.count", "v1"), new StructuredValue.ObjectValue(Map.of()));
        var request = new InvocationRequest<>(new DefinitionRef("capability", "tool", "v1"), new DefinitionRef("binding", "tool", "v1"),
                input.kind(), input, new ExecutionOptions(context.deadline(), 1, 1000, 0, 0), context);
        var error = state == Invocation.State.UNKNOWN ? new ExecutionError("UNKNOWN", ExecutionError.Phase.INVOCATION, false,
                ExecutionError.SideEffect.POSSIBLE, ExecutionError.Certainty.UNKNOWN, "trace") : null;
        return new Invocation(request, "a".repeat(64), null, null, null, state, 0, null, null, error, at, at);
    }

    @Test
    public void unknownIsNeverTreatedAsFinishedAndOtherScopesDoNotLeak() {
        var inspector = new JdbcWorkspaceRetentionInspector(source, source, codec, true);
        write("arte_ai_invocation", "i", invocation("space", Invocation.State.UNKNOWN));
        assertTrue(inspector.inspect(scope).blockers().stream().anyMatch(b -> b.code().equals("EXECUTION_UNKNOWN")));
        write("arte_ai_invocation", "i", invocation("space", Invocation.State.CANCELLED));
        assertTrue(inspector.inspect(scope).blockers().isEmpty());
        write("arte_ai_invocation", "other", invocation("other", Invocation.State.UNKNOWN));
        assertTrue(inspector.inspect(scope).blockers().isEmpty());
        assertEquals(Long.valueOf(1), inspector.inspect(scope).retainedResources().get("invocations"));
    }

    @Test
    public void bothAccountHeldAndReservationMustConvergeAndHistoryRemains() {
        var inspector = new JdbcWorkspaceRetentionInspector(source, source, codec, true);
        var owner = new ExecutionOwner("tenant", "space", "user");
        var rate = new DefinitionRef("rate", "rate", "v1");
        write("arte_ai_account", "a", new BudgetCommands.Account("budget", owner, money("10"), money("2"), money("1"), rate, 0));
        write("arte_ai_reservation", "r", new BudgetReservation("reservation", "budget", "i", "attempt", money("2"), rate,
                BudgetReservation.State.PENDING_RECONCILIATION, 0, at, at.plusSeconds(1)));
        assertEquals(2, inspector.inspect(scope).blockers().size());
        write("arte_ai_reservation", "r", new BudgetReservation("reservation", "budget", "i", "attempt", money("2"), rate,
                BudgetReservation.State.SETTLED, 1, at, at.plusSeconds(1)));
        assertTrue(inspector.inspect(scope).blockers().stream().anyMatch(b -> b.code().equals("BUDGET_HELD")));
        write("arte_ai_account", "a", new BudgetCommands.Account("budget", owner, money("10"), money("0"), money("3"), rate, 1));
        assertTrue(inspector.inspect(scope).blockers().isEmpty());
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_reservation", Integer.class));
    }

    @Test
    public void missingRuntimeSchemaAndSeparateDatabasesBlockRatherThanPretendEmpty() {
        jdbc.execute("DROP TABLE arte_ai_reservation");
        assertFalse(new JdbcWorkspaceRetentionInspector(source, source, codec, true).inspect(scope).blockers().isEmpty());
        assertFalse(new JdbcWorkspaceRetentionInspector(source, new JdbcDataSource(), codec, false).inspect(scope).blockers().isEmpty());
    }

    @Test
    public void toolRunAndBudgetChecksumsMustBeVerifiedBeforeClosure() {
        jdbc.execute("CREATE TABLE arte_ai_tool_chat_run(state_snapshot CLOB,state_digest VARCHAR(64))");
        jdbc.execute("CREATE TABLE arte_ai_tool_chat_budget(snapshot CLOB,digest VARCHAR(64))");
        var run = new ToolChatRun("run", new ExecutionOwner("tenant", "space", "user"), ToolChatRun.State.CANCELLED, 0,
                new ToolChatRun.Checkpoint(0, ToolChatRun.Phase.FINISHED, List.of()), 0, 0, null, null, at, at);
        String state = codec.encode(run), budget = codec.encode(new ToolChatBudget("run", money("10"), money("0"), money("0"), false, 0));
        jdbc.update("INSERT INTO arte_ai_tool_chat_run VALUES(?,?)", state, CanonicalJson.sha256(state));
        jdbc.update("INSERT INTO arte_ai_tool_chat_budget VALUES(?,?)", budget, CanonicalJson.sha256(budget));
        var inspector = new JdbcWorkspaceRetentionInspector(source, source, codec, true);
        assertTrue(inspector.inspect(scope).blockers().isEmpty());
        jdbc.update("UPDATE arte_ai_tool_chat_run SET state_digest=?", "0".repeat(64));
        assertThrows(IllegalStateException.class, () -> inspector.inspect(scope));
        jdbc.update("UPDATE arte_ai_tool_chat_run SET state_digest=?", CanonicalJson.sha256(state));
        jdbc.update("UPDATE arte_ai_tool_chat_budget SET digest=?", "0".repeat(64));
        assertThrows(IllegalStateException.class, () -> inspector.inspect(scope));
    }
}
