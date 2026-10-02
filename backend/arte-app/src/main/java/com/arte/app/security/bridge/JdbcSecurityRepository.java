package com.arte.app.security.bridge;

import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import com.arte.base.model.resource.ResourceRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 显式主体查询，绕开依赖旧 UserContext 的 MyBatis 自动数据过滤；不读取账号凭据。
 */
public class JdbcSecurityRepository {
    private final JdbcTemplate jdbc;

    public JdbcSecurityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Account(int id, String userName, boolean enabled, String revision) {
        public PrincipalRef principal() {
            return new PrincipalRef(Integer.toString(id), PrincipalType.USER);
        }
    }

    public record Membership(boolean enabled, String revision) {
    }

    public record Placement(String tenantId, String workspaceId, boolean enabled, String revision) {
    }

    public record Flag(boolean enabled, String revision) {
    }

    public record Task(String applicationId, String bindingId, boolean enabled, Instant validUntil, String revision) {
    }

    public record ApplicationPolicy(boolean applicationEnabled, boolean bindingEnabled, String revision) {
    }

    public record Connection(String origin, boolean enabled, String revision) {
    }

    public record Consent(String requestDigest, boolean enabled, Instant validUntil, String revision) {
    }

    public Optional<Account> accountByName(String name) {
        return one("SELECT id, user_name, status, row_version FROM arte_rbac_user WHERE user_name = ?",
                accountMapper(), name);
    }

    public Optional<Account> account(PrincipalRef principal) {
        if (principal.type() != PrincipalType.USER) return Optional.empty();
        Integer id = positiveId(principal.principalId());
        return id == null ? Optional.empty() : one(
                "SELECT id, user_name, status, row_version FROM arte_rbac_user WHERE id = ?", accountMapper(), id);
    }

    public Optional<Membership> membership(ExecutionContext context) {
        var scope = context.scope();
        return membership(scope.tenantId(), scope.workspaceId(), scope.principal());
    }

    public Optional<Membership> membership(String tenantId, String workspaceId, PrincipalRef principal) {
        return one("SELECT enabled, revision FROM arte_security_member WHERE tenant_id = ? AND workspace_id = ? AND user_id = ?",
                (rs, row) -> new Membership(rs.getBoolean("enabled"), rs.getString("revision")),
                tenantId, workspaceId, principal.principalId());
    }

    public Optional<Placement> placement(ResourceRef resource) {
        return one("SELECT tenant_id, workspace_id, enabled, revision FROM arte_security_resource WHERE resource_type = ? AND resource_id = ?",
                (rs, row) -> new Placement(rs.getString("tenant_id"), rs.getString("workspace_id"),
                        rs.getBoolean("enabled"), rs.getString("revision")), resource.resourceType(), resource.resourceId());
    }

    public Optional<Flag> resourceGrant(ResourceRef resource, PrincipalRef principal, String action) {
        return one("SELECT enabled, revision FROM arte_security_resource_grant WHERE resource_type = ? AND resource_id = ? AND user_id = ? AND action_code = ?",
                flagMapper(), resource.resourceType(), resource.resourceId(), principal.principalId(), action);
    }

    public Optional<Task> task(ExecutionContext context) {
        return one("SELECT application_id, binding_id, enabled, valid_until, revision FROM arte_security_task WHERE context_key = ?",
                (rs, row) -> new Task(rs.getString("application_id"), rs.getString("binding_id"),
                        rs.getBoolean("enabled"), rs.getTimestamp("valid_until").toInstant(), rs.getString("revision")),
                SecurityFingerprints.context(context));
    }

    public Optional<Flag> taskAction(ExecutionContext context, PrincipalRef executor, String action) {
        return one("SELECT enabled, revision FROM arte_security_task_action WHERE context_key = ? AND executor_type = ? AND executor_id = ? AND action_code = ?",
                flagMapper(), SecurityFingerprints.context(context), executor.type().name(), executor.principalId(), action);
    }

    public Optional<Flag> service(String id) {
        return one("SELECT enabled, revision FROM arte_security_service WHERE id = ?", flagMapper(), id);
    }

    public Optional<Flag> taskResourceAction(ExecutionContext context, ResourceRef resource, String action) {
        return one("SELECT enabled, revision FROM arte_security_task_resource_action WHERE context_key = ? AND resource_key = ? AND action_code = ?",
                flagMapper(), SecurityFingerprints.context(context), SecurityFingerprints.resource(resource), action);
    }

    public Optional<ApplicationPolicy> applicationPolicy(ExecutionContext context, Task task, String action) {
        return applicationPolicy(context.scope().tenantId(), context.scope().workspaceId(), task.applicationId(), task.bindingId(), action);
    }

    public Optional<ApplicationPolicy> applicationPolicy(String tenantId, String workspaceId, String applicationId, String bindingId, String action) {
        return one("SELECT application_enabled, binding_enabled, revision FROM arte_security_application_policy WHERE tenant_id = ? AND workspace_id = ? AND application_id = ? AND binding_id = ? AND action_code = ?",
                (rs, row) -> new ApplicationPolicy(rs.getBoolean("application_enabled"), rs.getBoolean("binding_enabled"), rs.getString("revision")),
                tenantId, workspaceId, applicationId, bindingId, action);
    }

    public Optional<Connection> connection(ExecutionContext context, ResourceRef connection) {
        return one("SELECT origin, enabled, revision FROM arte_security_connection WHERE tenant_id = ? AND workspace_id = ? AND connection_key = ?",
                (rs, row) -> new Connection(rs.getString("origin"), rs.getBoolean("enabled"), rs.getString("revision")),
                context.scope().tenantId(), context.scope().workspaceId(), SecurityFingerprints.resource(connection));
    }

    public Optional<Flag> egressRule(ExecutionContext context, Task task, ResourceRef connection, String purpose) {
        return one("SELECT enabled, revision FROM arte_security_egress_rule WHERE tenant_id = ? AND workspace_id = ? AND application_id = ? AND binding_id = ? AND connection_key = ? AND purpose = ?",
                flagMapper(), context.scope().tenantId(), context.scope().workspaceId(), task.applicationId(),
                task.bindingId(), SecurityFingerprints.resource(connection), purpose);
    }

    public Optional<Consent> consent(ResourceRef ref) {
        if (ref == null || !"egress-consent".equals(ref.resourceType()) || ref.version() == null || ref.isDraft()
                || ref.rangeRef() != null || ref.contentDigest() != null) return Optional.empty();
        return one("SELECT request_digest, enabled, valid_until, revision FROM arte_security_consent WHERE id = ? AND revision = ?",
                (rs, row) -> new Consent(rs.getString("request_digest"), rs.getBoolean("enabled"),
                        rs.getTimestamp("valid_until").toInstant(), rs.getString("revision")), ref.resourceId(), ref.version());
    }

    /**
     * 仅由已认证的新入口在事务内创建；授权策略缺失不会自动生成默认许可。
     */
    void saveTask(ExecutionContext context, String applicationId, String bindingId, Instant validUntil,
                  Map<ResourceRef, Set<String>> resourceActions) {
        String key = SecurityFingerprints.context(context);
        jdbc.update("INSERT INTO arte_security_task (context_key, application_id, binding_id, enabled, valid_until, revision) VALUES (?, ?, ?, TRUE, ?, 1)",
                key, applicationId, bindingId, Timestamp.from(validUntil));
        for (String action : context.authorizationScopes()) {
            jdbc.update("INSERT INTO arte_security_task_action (context_key, executor_type, executor_id, action_code, enabled, revision) VALUES (?, ?, ?, ?, TRUE, 1)",
                    key, context.scope().principal().type().name(), context.scope().principal().principalId(), action);
        }
        resourceActions.forEach((resource, actions) -> actions.forEach(action ->
                jdbc.update("INSERT INTO arte_security_task_resource_action (context_key, resource_key, action_code, enabled, revision) VALUES (?, ?, ?, TRUE, 1)",
                        key, SecurityFingerprints.resource(resource), action)));
    }

    void saveConsent(ResourceRef ref, String digest, Instant validUntil) {
        jdbc.update("INSERT INTO arte_security_consent (id, request_digest, enabled, valid_until, revision) VALUES (?, ?, TRUE, ?, ?)",
                ref.resourceId(), digest, Timestamp.from(validUntil), ref.version());
    }

    static Integer positiveId(String value) {
        try {
            int id = Integer.parseInt(value);
            return id > 0 && Integer.toString(id).equals(value) ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private RowMapper<Account> accountMapper() {
        return (rs, row) -> new Account(rs.getInt("id"), rs.getString("user_name"),
                rs.getInt("status") == 1, rs.getString("row_version"));
    }

    private RowMapper<Flag> flagMapper() {
        return (rs, row) -> new Flag(rs.getBoolean("enabled"), rs.getString("revision"));
    }

    private <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... args) {
        List<T> values = jdbc.query(sql, mapper, args);
        if (values.size() > 1) throw new IllegalStateException("ambiguous security identity");
        return values.stream().findFirst();
    }
}
