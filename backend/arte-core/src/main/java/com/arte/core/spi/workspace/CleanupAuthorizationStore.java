package com.arte.core.spi.workspace;

import com.arte.core.pojo.workspace.CleanupAuthorization;

import java.util.Optional;

/**
 * 内部受限收尾授权存储，即保存、查询、更新收尾授权记录。
 * <p>
 * 目标及操作绑定不可被 Worker 扩展；更新采用版本条件，过期续发由受信治理服务执行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface CleanupAuthorizationStore {

    /**
     * 按授权 ID 查询受限收尾授权，不存在时返回空。
     */
    Optional<CleanupAuthorization> find(String authorizationId);

    /**
     * 新增受限收尾授权，唯一条件冲突时返回 false。
     */
    boolean insert(CleanupAuthorization authorization);

    /**
     * 按预期版本更新收尾授权，版本条件不满足时返回 false。
     */
    boolean replace(CleanupAuthorization authorization, long expectedVersion);
}
