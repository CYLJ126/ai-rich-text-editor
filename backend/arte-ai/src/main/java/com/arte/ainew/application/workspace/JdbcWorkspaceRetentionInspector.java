package com.arte.ainew.application.workspace;

import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.BudgetReservation;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.tool.ToolChatBudget;
import com.arte.ainew.pojo.tool.ToolChatRun;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.core.pojo.platform.ExecutionOwner;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.ClosureBlocker;
import com.arte.core.spi.workspace.WorkspaceClosureInspector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import javax.sql.DataSource;
import java.util.*;
import java.util.function.Consumer;

/**
 * 只读检查权威执行和资金状态。保留正文和原 owner；UNKNOWN 即使 terminal() 返回 true 也必须阻塞关闭。
 * 首版限制全库候选扫描预算，超限或损坏快照保留阻塞，不把无法扫描当作没有任务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/10 18:40 ✾
 */
public final class JdbcWorkspaceRetentionInspector implements WorkspaceClosureInspector {

    private static final int SCAN_LIMIT = 10000;
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final ExecutionRecordCodec codec;
    private final boolean sameDatabase;
    private final boolean runtimeEnabled;

    public JdbcWorkspaceRetentionInspector(DataSource source, DataSource platform, ExecutionRecordCodec codec, boolean enabled) {
        this.source = source;
        this.jdbc = new JdbcTemplate(source);
        this.jdbc.setQueryTimeout(10);
        this.jdbc.setFetchSize(100);
        this.codec = codec;
        this.sameDatabase = source == platform;
        this.runtimeEnabled = enabled;
    }

    @Override
    public String domain() {
        return "ai";
    }

    private boolean owns(Scope scope, ExecutionOwner owner) {
        return scope.tenantId().equals(owner.tenantId()) && scope.workspaceId().equals(owner.workspaceId());
    }

    private void block(List<ClosureBlocker> blockers, String code) {
        if (blockers.stream().noneMatch(b -> b.code().equals(code)))
            blockers.add(new ClosureBlocker(domain(), "retention", code, true));
    }

    private Set<String> tables() {
        try (var connection = source.getConnection(); var rows = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
            Set<String> result = new HashSet<>();
            while (rows.next()) result.add(rows.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            return result;
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException("Cannot verify AI retention schema", error);
        }
    }

    private <T> void scan(String table, String column, Class<T> type, Consumer<T> consume, List<ClosureBlocker> blockers) {
        scan(table, column, null, type, consume, blockers);
    }

    private <T> void scan(String table, String column, String checksum, Class<T> type, Consumer<T> consume, List<ClosureBlocker> blockers) {
        int[] count = {0};
        jdbc.query("SELECT " + column + (checksum == null ? "" : "," + checksum) + " FROM " + table + " LIMIT " + (SCAN_LIMIT + 1), (RowCallbackHandler) row -> {
            if (++count[0] > SCAN_LIMIT) {
                block(blockers, "SCAN_LIMIT_REACHED");
                return;
            }
            String snapshot = row.getString(1);
            if (checksum != null && !CanonicalJson.sha256(snapshot).equals(row.getString(2)))
                throw new IllegalStateException("Cannot verify AI retention snapshot");
            consume.accept(codec.decode(snapshot, type));
        });
    }

    @Override
    public Inspection inspect(Scope scope) {
        List<ClosureBlocker> blockers = new ArrayList<>();
        Map<String, Long> counts = new TreeMap<>();
        if (!sameDatabase) {
            block(blockers, "AI_SEPARATE_DATASOURCE");
            return new Inspection(blockers, counts);
        }
        Set<String> tables = tables();
        Set<String> core = Set.of("arte_ai_invocation", "arte_ai_account", "arte_ai_reservation");
        if (Collections.disjoint(core, tables) && !runtimeEnabled)
            return new Inspection(List.of(), Map.of("invocations", 0L));
        if (!tables.containsAll(core)) {
            block(blockers, "AI_SCHEMA_UNAVAILABLE");
            return new Inspection(blockers, counts);
        }
        Map<String, BudgetCommands.Account> accounts = new HashMap<>();
        Set<String> allAccounts = new HashSet<>();
        scan("arte_ai_account", "workspace_snapshot", BudgetCommands.Account.class, account -> {
            allAccounts.add(account.budgetRef());
            if (!owns(scope, account.owner())) return;
            accounts.put(account.budgetRef(), account);
            counts.merge("accounts", 1L, Long::sum);
            if (account.held().amount().signum() != 0) block(blockers, "BUDGET_HELD");
        }, blockers);
        scan("arte_ai_invocation", "workspace_snapshot", Invocation.class, invocation -> {
            if (!owns(scope, ExecutionOwners.from(invocation.request().context()))) return;
            counts.merge("invocations", 1L, Long::sum);
            if (invocation.state() == Invocation.State.UNKNOWN) block(blockers, "EXECUTION_UNKNOWN");
            else if (!invocation.state().terminal()) block(blockers, "EXECUTION_ACTIVE");
        }, blockers);
        scan("arte_ai_reservation", "workspace_snapshot", BudgetReservation.class, reservation -> {
            if (!allAccounts.contains(reservation.budgetRef())) block(blockers, "ORPHAN_RESERVATION");
            if (accounts.containsKey(reservation.budgetRef()) && (reservation.state() == BudgetReservation.State.RESERVED
                    || reservation.state() == BudgetReservation.State.PENDING_RECONCILIATION))
                block(blockers, "RESERVATION_UNSETTLED");
        }, blockers);
        if (tables.contains("arte_ai_tool_chat_run")) {
            Set<String> runs = new HashSet<>();
            scan("arte_ai_tool_chat_run", "state_snapshot", "state_digest", ToolChatRun.class, run -> {
                if (!owns(scope, run.owner())) return;
                runs.add(run.runId());
                counts.merge("toolRuns", 1L, Long::sum);
                if (!run.state().terminal()) block(blockers, "TOOL_RUN_ACTIVE");
            }, blockers);
            if (!tables.contains("arte_ai_tool_chat_budget")) block(blockers, "TOOL_BUDGET_SCHEMA_UNAVAILABLE");
            else scan("arte_ai_tool_chat_budget", "snapshot", "digest", ToolChatBudget.class, budget -> {
                if (runs.contains(budget.runId()) && budget.held().amount().signum() != 0)
                    block(blockers, "TOOL_BUDGET_HELD");
            }, blockers);
        }
        return new Inspection(blockers, counts);
    }
}
