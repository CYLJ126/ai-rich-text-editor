package com.arte.app.platform.persistence;

import com.arte.core.enums.workspace.ClosureWorkStateEnum;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.PageResult;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.ClosureBlocker;
import com.arte.core.pojo.workspace.ClosureOperation;
import com.arte.core.pojo.workspace.ClosureWorkItem;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.ClosureWorkStore;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 同库关闭操作及租约持久化。所有更新参与原治理事务，不依赖进程内锁。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/10 18:40 ✾
 */
public final class JdbcWorkspaceClosureStore implements ClosureWorkStore {

    private static final String OPERATIONS = "arte_platform_workspace_closure";
    private static final String WORK = "arte_platform_workspace_closure_work";
    private final JdbcTemplate jdbc;
    private final PlatformJson json;
    private final PublicTransactionRunner tx;

    /**
     * 封闭存储信封，不使用默认多态。
     */
    public record Work(ClosureWorkItem item, List<ClosureBlocker> blockers) {
        public Work {
            blockers = List.copyOf(blockers);
        }
    }

    public JdbcWorkspaceClosureStore(DataSource source, PublicTransactionRunner tx, PlatformJson json) {
        this.jdbc = new JdbcTemplate(source);
        this.tx = tx;
        this.json = json;
    }

    @Override
    public boolean ready() {
        try {
            jdbc.queryForList("SELECT id,tenant_id,workspace_id,state,version,updated_at,payload FROM " + OPERATIONS + " WHERE 1=0");
            jdbc.queryForList("SELECT operation_id,domain,work_key,version,fencing,lease_until,payload FROM " + WORK + " WHERE 1=0");
            return true;
        } catch (DataAccessException unavailable) {
            return false;
        }
    }

    private ClosureOperation operation(ResultSet row, int index) throws SQLException {
        ClosureOperation value = json.read(row.getString("payload"), ClosureOperation.class);
        if (!value.id().equals(row.getString("id")) || !value.scope().tenantId().equals(row.getString("tenant_id"))
                || !value.scope().workspaceId().equals(row.getString("workspace_id")) || value.version() != row.getLong("version")
                || value.state().getValue() != row.getInt("state"))
            throw new IllegalStateException("Invalid closure envelope");
        return value;
    }

    private Work work(ResultSet row, int index) throws SQLException {
        Work value = json.read(row.getString("payload"), Work.class);
        if (!value.item().operationId().equals(row.getString("operation_id")) || !value.item().domain().equals(row.getString("domain"))
                || !value.item().workKey().equals(row.getString("work_key")) || value.item().version() != row.getLong("version"))
            throw new IllegalStateException("Invalid closure work envelope");
        return value;
    }

    private static LocalDateTime at(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @Override
    public Optional<ClosureOperation> findOperation(Scope scope, String id) {
        return find(scope, id, false);
    }

    @Override
    public Optional<ClosureOperation> lockOperation(Scope scope, String id) {
        tx.requireWritable();
        return find(scope, id, true);
    }

    private Optional<ClosureOperation> find(Scope scope, String id, boolean lock) {
        return jdbc.query("SELECT * FROM " + OPERATIONS + " WHERE tenant_id=? AND workspace_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
                this::operation, scope.tenantId(), scope.workspaceId(), id).stream().findFirst();
    }

    @Override
    public Optional<ClosureOperation> findForWorkspace(Scope scope) {
        return jdbc.query("SELECT * FROM " + OPERATIONS + " WHERE tenant_id=? AND workspace_id=?", this::operation,
                scope.tenantId(), scope.workspaceId()).stream().findFirst();
    }

    @Override
    public boolean insertOperation(ClosureOperation operation) {
        tx.requireWritable();
        try {
            return jdbc.update("INSERT INTO " + OPERATIONS + "(id,tenant_id,workspace_id,state,version,updated_at,payload) VALUES(?,?,?,?,?,?,?)",
                    operation.id(), operation.scope().tenantId(), operation.scope().workspaceId(), operation.state().getValue(), operation.version(),
                    at(operation.updatedAt()), json.write(operation)) == 1;
        } catch (DuplicateKeyException conflict) {
            return false;
        }
    }

    @Override
    public boolean replaceOperation(ClosureOperation operation, long expected) {
        tx.requireWritable();
        if (operation.version() != expected + 1) throw new IllegalArgumentException("Closure version");
        return jdbc.update("UPDATE " + OPERATIONS + " SET state=?,version=?,updated_at=?,payload=? WHERE id=? AND tenant_id=? AND workspace_id=? AND version=?",
                operation.state().getValue(), operation.version(), at(operation.updatedAt()), json.write(operation), operation.id(),
                operation.scope().tenantId(), operation.scope().workspaceId(), expected) == 1;
    }

    @Override
    public PageResult<ClosureOperation> listUnfinished(PageQuery query) {
        return operations("state<>3", List.of(), query);
    }

    private PageResult<ClosureOperation> operations(String where, List<Object> args, PageQuery query) {
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + OPERATIONS + " c WHERE " + where, Long.class, args.toArray());
        List<Object> page = new ArrayList<>(args);
        page.add(query.size());
        page.add(Math.multiplyExact(query.current() - 1, query.size()));
        var rows = jdbc.query("SELECT c.* FROM " + OPERATIONS + " c WHERE " + where + " ORDER BY c.updated_at,c.id LIMIT ? OFFSET ?", this::operation, page.toArray());
        return new PageResult<>(query.current(), query.size(), total, rows);
    }

    @Override
    public PageResult<ClosureOperation> listForGovernor(String subject, PageQuery query) {
        return operations("EXISTS(SELECT 1 FROM arte_platform_tenant t JOIN arte_platform_tenant_member tm ON tm.tenant_id=t.id"
                + " WHERE t.id=c.tenant_id AND t.state=0 AND tm.subject_id=? AND tm.state=0 AND (tm.role=0 OR EXISTS("
                + "SELECT 1 FROM arte_platform_workspace_member wm WHERE wm.tenant_id=c.tenant_id AND wm.workspace_id=c.workspace_id"
                + " AND wm.subject_id=? AND wm.state=0 AND wm.role=0)))", List.of(subject, subject), query);
    }

    @Override
    public boolean insertWorkIfAbsent(ClosureWorkItem item) {
        tx.requireWritable();
        try {
            return jdbc.update("INSERT INTO " + WORK + "(operation_id,domain,work_key,version,fencing,lease_until,payload) VALUES(?,?,?,?,0,NULL,?)",
                    item.operationId(), item.domain(), item.workKey(), item.version(), json.write(new Work(item, List.of()))) == 1;
        } catch (DuplicateKeyException conflict) {
            return false;
        }
    }

    @Override
    public PageResult<ClosureWorkItem> listWork(Scope scope, String id, PageQuery query) {
        if (findOperation(scope, id).isEmpty()) return new PageResult<>(query.current(), query.size(), 0, List.of());
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + WORK + " WHERE operation_id=?", Long.class, id);
        var rows = jdbc.query("SELECT * FROM " + WORK + " WHERE operation_id=? ORDER BY domain,work_key LIMIT ? OFFSET ?",
                this::work, id, query.size(), Math.multiplyExact(query.current() - 1, query.size()));
        return new PageResult<>(query.current(), query.size(), total, rows.stream().map(Work::item).toList());
    }

    @Override
    public Optional<ClosureWorkItem> claim(Scope scope, String id, String domain, String key, long expected,
                                           String owner, Instant now, Duration duration) {
        tx.requireWritable();
        if (lockOperation(scope, id).isEmpty()) return Optional.empty();
        var rows = jdbc.query("SELECT * FROM " + WORK + " WHERE operation_id=? AND domain=? AND work_key=? FOR UPDATE", this::work, id, domain, key);
        if (rows.isEmpty()) return Optional.empty();
        var old = rows.getFirst();
        if (old.item().version() != expected || old.item().leaseUntil() != null && old.item().leaseUntil().isAfter(now))
            return Optional.empty();
        long fencing = Math.incrementExact(jdbc.queryForObject("SELECT fencing FROM " + WORK + " WHERE operation_id=? AND domain=? AND work_key=?", Long.class, id, domain, key));
        var next = new ClosureWorkItem(id, scope, domain, key, old.item().targetRefs(), ClosureWorkStateEnum.RUNNING,
                expected + 1, owner, fencing, now.plus(duration));
        int count = jdbc.update("UPDATE " + WORK + " SET version=?,fencing=?,lease_until=?,payload=? WHERE operation_id=? AND domain=? AND work_key=? AND version=?",
                next.version(), fencing, at(next.leaseUntil()), json.write(new Work(next, old.blockers())), id, domain, key, expected);
        return count == 1 ? Optional.of(next) : Optional.empty();
    }

    @Override
    public boolean replaceWork(ClosureWorkItem item, long version, long fencing) {
        return replaceProgress(item, version, fencing, List.of());
    }

    @Override
    public boolean replaceProgress(ClosureWorkItem item, long expected, long fencing, List<ClosureBlocker> blockers) {
        tx.requireWritable();
        if (item.version() != expected + 1) throw new IllegalArgumentException("Closure work version");
        return jdbc.update("UPDATE " + WORK + " SET version=?,lease_until=?,payload=? WHERE operation_id=? AND domain=? AND work_key=? AND version=? AND fencing=?",
                item.version(), at(item.leaseUntil()), json.write(new Work(item, blockers)), item.operationId(), item.domain(), item.workKey(), expected, fencing) == 1;
    }

    @Override
    public List<ClosureBlocker> blockers(Scope scope, String id) {
        if (findOperation(scope, id).isEmpty()) return List.of();
        return jdbc.query("SELECT * FROM " + WORK + " WHERE operation_id=? ORDER BY domain,work_key", this::work, id)
                .stream().flatMap(value -> value.blockers().stream()).toList();
    }
}
