package com.arte.app.platform.workspace;

import com.arte.app.platform.persistence.WorkspaceCursorStore;
import com.arte.core.api.workspace.WorkspaceService;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.WorkspaceBrowseQuery;
import com.arte.core.pojo.workspace.WorkspaceCandidateKey;
import com.arte.core.pojo.workspace.WorkspaceViews;

import java.util.List;

/**
 * 空间目录浏览适配器，将内部候选扫描边界转换为客户端使用的不透明游标令牌。
 * <p>
 * 继续浏览时验证游标绑定的主体、结果条数和规范化搜索条件，再调用核心服务重新检查访问资格。
 * 游标仅保存导航状态，不缓存授权决定，也不能代替当前授权检查。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 12:35 ✾
 */
public final class WorkspaceDirectoryBrowser {

    private final WorkspaceService workspaces;
    private final WorkspaceCursorStore cursors;

    public WorkspaceDirectoryBrowser(WorkspaceService workspaces, WorkspaceCursorStore cursors) {
        this.workspaces = java.util.Objects.requireNonNull(workspaces);
        this.cursors = java.util.Objects.requireNonNull(cursors);
    }

    /**
     * 面向客户端的空间目录浏览结果，不包含可见总数。
     *
     * @param records    当前可访问且符合搜索条件的空间摘要，复制为不可变列表；可能不足请求条数或为空
     * @param nextCursor 继续浏览的不透明游标令牌，已扫描到候选末尾时为 null；有令牌不保证后续存在可见结果
     * @param exhausted  本次扫描是否已到达候选末尾，不代表固定时点的目录快照；为 true 时 nextCursor 必须为 null
     */
    public record Page(List<WorkspaceViews.WorkspaceSummary> records, String nextCursor, boolean exhausted) {
        public Page {
            records = List.copyOf(records);
            if (exhausted != (nextCursor == null)) throw new IllegalArgumentException("Invalid navigation page");
        }
    }

    /**
     * 按预算浏览空间目录，并在仍可继续扫描时保存新的公开游标。
     * <p>
     * 首次浏览从目录起点开始；继续浏览必须使用同一主体、结果条数和规范化搜索条件。
     * 每次调用均由核心服务重新验证当前访问资格；返回空列表但 exhausted 为 false 时，客户端仍可继续浏览。
     *
     * @param actor 经可信认证适配确认的当前操作者
     * @param token 上次返回的不透明游标令牌，首次浏览传 null
     * @param query 本次浏览的结果条数上限及规范化搜索条件
     * @return 空间摘要、后续游标以及是否已扫描到候选末尾
     * @throws IllegalArgumentException                           游标令牌格式不合法，或游标绑定的主体、结果条数、搜索条件与本次请求不一致
     * @throws com.arte.core.exception.platform.PlatformException 游标不存在或已失效，或游标存储、当前授权验证不可用
     */
    public Page browse(TrustedActor actor, String token, WorkspaceBrowseQuery query) {
        WorkspaceCandidateKey after = null;
        if (token != null) {
            var cursor = cursors.load(token);
            if (!cursor.subjectId().equals(actor.principal().subjectId()) || cursor.size() != query.size()
                    || !cursor.search().equals(query.search()))
                throw new IllegalArgumentException("Cursor context mismatch");
            after = cursor.key();
        }
        var result = workspaces.browseAccessible(actor, query, after);
        String next = result.exhausted() ? null : cursors.save(new WorkspaceCursorStore.Cursor(
                actor.principal().subjectId(), 2, query.size(), query.search(), result.nextBoundary()));
        return new Page(result.records(), next, result.exhausted());
    }
}
