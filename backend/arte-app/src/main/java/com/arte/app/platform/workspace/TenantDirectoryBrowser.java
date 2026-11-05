package com.arte.app.platform.workspace;

import com.arte.app.platform.persistence.WorkspaceCursorStore;
import com.arte.core.api.workspace.TenantQueryService;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.TenantBrowseQuery;
import com.arte.core.pojo.workspace.TenantDirectoryViews;

import java.util.List;

/**
 * 租户目录游标只保存续扫位置和条件，当前资格由核心查询重新检查。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 13:11 ✾
 */
public final class TenantDirectoryBrowser {

    private final TenantQueryService tenants;
    private final WorkspaceCursorStore cursors;

    public TenantDirectoryBrowser(TenantQueryService tenants, WorkspaceCursorStore cursors) {
        this.tenants = tenants;
        this.cursors = cursors;
    }

    public record Page(List<TenantDirectoryViews.Summary> records, String nextCursor, boolean exhausted) {
        public Page {
            records = List.copyOf(records);
            if (exhausted != (nextCursor == null)) throw new IllegalArgumentException("Invalid tenant navigation page");
        }
    }

    public Page browse(TrustedActor actor, String token, TenantBrowseQuery query) {
        String subjectId = actor.principal().subjectId();
        String after = null;
        if (token != null) {
            var cursor = cursors.loadTenant(token);
            if (!cursor.subjectId().equals(subjectId) || cursor.size() != query.size()
                    || !cursor.search().equals(query.search()) || cursor.type() != query.type())
                throw new IllegalArgumentException("Cursor context mismatch");
            after = cursor.afterTenantId();
        }
        var page = tenants.browseAccessible(actor, query, after);
        String next = page.exhausted() ? null : cursors.saveTenant(new WorkspaceCursorStore.TenantCursor(
                subjectId, 1, query.size(), query.search(), query.type(), page.nextBoundary()));
        return new Page(page.records(), next, page.exhausted());
    }
}
