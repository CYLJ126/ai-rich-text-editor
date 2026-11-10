package com.arte.app.platform.workspace;

import com.arte.ainew.pojo.model.ModelResource;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.ClosureBlocker;
import com.arte.core.spi.workspace.WorkspaceClosureInspector;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 注册资源保留计数及租户模型管理依赖检查，不读取或删除私有资源正文。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/10 18:40 ✾
 */
public final class WorkspaceResourceRetentionInspector implements WorkspaceClosureInspector {

    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final PlatformJson json;

    public WorkspaceResourceRetentionInspector(DataSource source, PlatformJson json) {
        this.source = source;
        this.jdbc = new JdbcTemplate(source);
        this.json = json;
        jdbc.setQueryTimeout(10);
    }

    @Override
    public String domain() {
        return "resources";
    }

    private boolean table(String name) {
        try (var c = source.getConnection(); var rows = c.getMetaData().getTables(c.getCatalog(), c.getSchema(), "%", new String[]{"TABLE"})) {
            while (rows.next()) if (name.equalsIgnoreCase(rows.getString("TABLE_NAME"))) return true;
            return false;
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Cannot verify resource schema", failure);
        }
    }

    @Override
    public Inspection inspect(Scope scope) {
        Map<String, Long> counts = new TreeMap<>();
        List<ClosureBlocker> blockers = new ArrayList<>();
        if (table("arte_platform_resource_access")) {
            jdbc.query("SELECT resource_type,COUNT(*) AS resource_count FROM arte_platform_resource_access WHERE tenant_id=? AND workspace_id=? GROUP BY resource_type",
                    (org.springframework.jdbc.core.RowCallbackHandler) row -> counts.put(row.getString("resource_type"), row.getLong("resource_count")), scope.tenantId(), scope.workspaceId());
        }
        if (table("arte_ai_model_resource")) {
            var models = jdbc.queryForList("SELECT payload FROM arte_ai_model_resource WHERE tenant_id=? AND workspace_id=? LIMIT 1001", String.class, scope.tenantId(), scope.workspaceId());
            if (models.size() > 1000)
                blockers.add(new ClosureBlocker(domain(), "retention", "SCAN_LIMIT_REACHED", true));
            for (String payload : models) {
                ModelResource model = json.read(payload, ModelResource.class);
                if (model.ownership().workspaceId() == null && model.state() != ModelResource.State.DISABLED) {
                    blockers.add(new ClosureBlocker(domain(), "retention", "TENANT_MODEL_DEPENDENCY", false));
                    break;
                }
            }
        }
        return new Inspection(blockers, counts);
    }
}
