package com.arte.app.platform.persistence;

import com.arte.core.enums.workspace.InvitationStateEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.PageResult;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.TenantInvitation;
import com.arte.core.pojo.workspace.WorkspaceInvitation;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.InvitationStore;
import com.arte.core.spi.workspace.TenantInvitationStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 两类邀请共用物理表，空 workspace_id 表示纯租户邀请。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 21:08 ✾
 */
public final class JdbcMembershipInvitationStore implements InvitationStore, TenantInvitationStore {

    private static final String TABLE = "arte_platform_membership_invitation";
    private final JdbcTemplate jdbc;
    private final PublicTransactionRunner tx;

    public JdbcMembershipInvitationStore(DataSource source, PublicTransactionRunner tx) {
        this.jdbc = new JdbcTemplate(source);
        this.tx = tx;
    }

    private record Row(String id, String tenant, String space, String target, String issuer, int role,
                       InvitationStateEnum state, Instant expiry, String accepted, long version) {
        TenantInvitation tenantInvitation() {
            return new TenantInvitation(id, tenant, target, state, expiry, issuer, accepted, version);
        }

        WorkspaceInvitation workspaceInvitation() {
            return new WorkspaceInvitation(id, new Scope(tenant, space), target, WorkspaceRoleEnum.values()[role],
                    state, expiry, issuer, accepted, version);
        }
    }

    private static Row readRow(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new Row(rs.getString("id"), rs.getString("tenant_id"), rs.getString("workspace_id"),
                rs.getString("target_subject_id"), rs.getString("issued_by"), rs.getInt("role"),
                InvitationStateEnum.values()[rs.getInt("state")], rs.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getString("accepted_member_id"), rs.getLong("version"));
    }

    private Optional<Row> find(String id, boolean workspace, boolean current) {
        if (current) {
            tx.requireWritable();
        }
        return jdbc.query("SELECT * FROM " + TABLE + " WHERE id=? AND workspace_id IS "
                        + (workspace ? "NOT NULL" : "NULL") + (current ? " FOR UPDATE" : ""),
                JdbcMembershipInvitationStore::readRow, id).stream().findFirst();
    }

    private boolean insert(Row row) {
        tx.requireWritable();
        try {
            return jdbc.update("INSERT INTO " + TABLE + " (id,tenant_id,workspace_id,target_subject_id,issued_by,role,state,expires_at,accepted_member_id,version)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    row.id(), row.tenant(), row.space(), row.target(), row.issuer(), row.role(), row.state().getValue(),
                    LocalDateTime.ofInstant(row.expiry(), ZoneOffset.UTC), row.accepted(), row.version()) == 1;
        } catch (DuplicateKeyException conflict) {
            return false;
        }
    }

    private boolean replace(Row row, long expected) {
        tx.requireWritable();
        if (row.version() != Math.incrementExact(expected)) {
            throw new IllegalArgumentException("Invalid invitation version");
        }
        return jdbc.update("UPDATE " + TABLE + " SET state=?, accepted_member_id=?, version=? WHERE id=? AND version=?",
                row.state().getValue(), row.accepted(), row.version(), row.id(), expected) == 1;
    }

    private PageResult<Row> list(String where, List<Object> arguments, PageQuery query) {
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE + " WHERE " + where, Long.class, arguments.toArray());
        List<Object> page = new ArrayList<>(arguments);
        page.add(query.size());
        page.add(Math.multiplyExact(query.current() - 1, query.size()));
        List<Row> rows = jdbc.query("SELECT * FROM " + TABLE + " WHERE " + where + " ORDER BY state, expires_at DESC, id LIMIT ? OFFSET ?",
                JdbcMembershipInvitationStore::readRow, page.toArray());
        return new PageResult<>(query.current(), query.size(), total, rows);
    }

    @Override
    public Optional<WorkspaceInvitation> find(String id) {
        return find(id, true, false).map(Row::workspaceInvitation);
    }

    @Override
    public Optional<WorkspaceInvitation> lock(String id) {
        return find(id, true, true).map(Row::workspaceInvitation);
    }

    private static Row row(WorkspaceInvitation invitation) {
        return new Row(invitation.id(), invitation.scope().tenantId(), invitation.scope().workspaceId(), invitation.targetSubjectId(),
                invitation.issuedBy(), invitation.role().getValue(), invitation.state(), invitation.expiresAt(),
                invitation.acceptedMemberId(), invitation.version());
    }

    @Override
    public boolean insert(WorkspaceInvitation invitation) {
        return insert(row(invitation));
    }

    @Override
    public boolean replace(WorkspaceInvitation invitation, long expected) {
        return replace(row(invitation), expected);
    }

    @Override
    public PageResult<WorkspaceInvitation> listForSubject(String subjectId, PageQuery query) {
        return list("workspace_id IS NOT NULL AND target_subject_id=?", List.of(subjectId), query).map(Row::workspaceInvitation);
    }

    @Override
    public PageResult<WorkspaceInvitation> listForWorkspace(Scope scope, PageQuery query) {
        return list("tenant_id=? AND workspace_id=?", List.of(scope.tenantId(), scope.workspaceId()), query).map(Row::workspaceInvitation);
    }

    @Override
    public Optional<TenantInvitation> findTenantInvitation(String id) {
        return find(id, false, false).map(Row::tenantInvitation);
    }

    @Override
    public Optional<TenantInvitation> lockTenantInvitation(String id) {
        return find(id, false, true).map(Row::tenantInvitation);
    }

    private static Row row(TenantInvitation invitation) {
        return new Row(invitation.id(), invitation.tenantId(), null, invitation.targetSubjectId(), invitation.issuedBy(), 2,
                invitation.state(), invitation.expiresAt(), invitation.acceptedMemberId(), invitation.version());
    }

    @Override
    public boolean insertTenantInvitation(TenantInvitation invitation) {
        return insert(row(invitation));
    }

    @Override
    public boolean replaceTenantInvitation(TenantInvitation invitation, long expected) {
        return replace(row(invitation), expected);
    }

    @Override
    public PageResult<TenantInvitation> listTenantInvitations(String tenantId, String subjectId, PageQuery query) {
        return (tenantId != null ? list("workspace_id IS NULL AND tenant_id=?", List.of(tenantId), query)
                : list("workspace_id IS NULL AND target_subject_id=?", List.of(subjectId), query)).map(Row::tenantInvitation);
    }

    @Override
    public void invalidatePending(String tenantId, String workspaceId, String subjectId) {
        tx.requireWritable();
        if (workspaceId == null) {
            jdbc.update("UPDATE " + TABLE + " SET state=2,version=version+1 WHERE tenant_id=? AND target_subject_id=? AND state=0",
                    tenantId, subjectId);
        } else {
            jdbc.update("UPDATE " + TABLE + " SET state=2,version=version+1 WHERE tenant_id=? AND workspace_id=? AND target_subject_id=? AND state=0",
                    tenantId, workspaceId, subjectId);
        }
    }

    private String stateWhere(InvitationStateEnum state, Instant now, List<Object> args) {
        if (state == null) return "";
        if (state == InvitationStateEnum.PENDING || state == InvitationStateEnum.EXPIRED) {
            args.add(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
            if (state == InvitationStateEnum.PENDING) return " AND state=0 AND expires_at>?";
            return " AND (state=3 OR (state=0 AND expires_at<=?))";
        }
        args.add(state.getValue());
        return " AND state=?";
    }

    @Override
    public Optional<WorkspaceInvitation> findPending(Scope scope, String subjectId, Instant now) {
        tx.requireWritable();
        return jdbc.query("SELECT * FROM " + TABLE + " WHERE tenant_id=? AND workspace_id=? AND target_subject_id=?"
                                + " AND state=0 AND expires_at>? ORDER BY id LIMIT 1 FOR UPDATE", JdbcMembershipInvitationStore::readRow,
                        scope.tenantId(), scope.workspaceId(), subjectId, LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .stream().findFirst().map(Row::workspaceInvitation);
    }

    @Override
    public Optional<TenantInvitation> findPendingTenant(String tenantId, String subjectId, Instant now) {
        tx.requireWritable();
        return jdbc.query("SELECT * FROM " + TABLE + " WHERE tenant_id=? AND workspace_id IS NULL AND target_subject_id=?"
                                + " AND state=0 AND expires_at>? ORDER BY id LIMIT 1 FOR UPDATE", JdbcMembershipInvitationStore::readRow,
                        tenantId, subjectId, LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .stream().findFirst().map(Row::tenantInvitation);
    }

    @Override
    public PageResult<WorkspaceInvitation> listForSubject(String subjectId, PageQuery query, InvitationStateEnum state, Instant now) {
        List<Object> args = new ArrayList<>(List.of(subjectId));
        return list("workspace_id IS NOT NULL AND target_subject_id=?" + stateWhere(state, now, args), args, query).map(Row::workspaceInvitation);
    }

    @Override
    public PageResult<WorkspaceInvitation> listForWorkspace(Scope scope, PageQuery query, InvitationStateEnum state, Instant now) {
        List<Object> args = new ArrayList<>(List.of(scope.tenantId(), scope.workspaceId()));
        return list("tenant_id=? AND workspace_id=?" + stateWhere(state, now, args), args, query).map(Row::workspaceInvitation);
    }

    @Override
    public PageResult<TenantInvitation> listTenantInvitations(String tenantId, String subjectId, PageQuery query, InvitationStateEnum state, Instant now) {
        List<Object> args = new ArrayList<>(List.of(tenantId != null ? tenantId : subjectId));
        return list("workspace_id IS NULL AND " + (tenantId != null ? "tenant_id=?" : "target_subject_id=?")
                + stateWhere(state, now, args), args, query).map(Row::tenantInvitation);
    }

    private long countPending(String subject, Instant now, boolean workspace) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE + " WHERE target_subject_id=? AND workspace_id IS "
                        + (workspace ? "NOT NULL" : "NULL") + " AND state=0 AND expires_at>?", Long.class,
                subject, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    @Override
    public long countPendingForSubject(String subjectId, Instant now) {
        return countPending(subjectId, now, true);
    }

    @Override
    public long countPendingTenantForSubject(String subjectId, Instant now) {
        return countPending(subjectId, now, false);
    }

}
