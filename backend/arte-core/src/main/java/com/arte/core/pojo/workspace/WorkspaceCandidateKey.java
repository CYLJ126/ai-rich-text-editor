package com.arte.core.pojo.workspace;

import com.arte.core.pojo.platform.Scope;

import java.time.Instant;
import java.util.Objects;

/**
 * 内部候选扫描的稳定键（不可变排序键）：创建时间降序、租户 ID 与空间 ID 升序。
 * 创建时间和完整范围在创建后不可变；更新、改名和成员状态不参与游标排序（不判断记录有没有被修改）。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 12:10 ✾
 */
public record WorkspaceCandidateKey(Instant createdAt, Scope scope) implements Comparable<WorkspaceCandidateKey> {

    public WorkspaceCandidateKey {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(scope, "scope");
    }

    public static WorkspaceCandidateKey from(Workspace workspace) {
        return new WorkspaceCandidateKey(workspace.createdAt(), workspace.scope());
    }

    /**
     * 按创建时间降序比较；时间相同时，再按租户 ID、空间 ID 的字典序升序比较。
     * <ol>
     *     <li>> 0：当前候选排在上一个之后，正常继续。</li>
     *     <li>= 0：排序键重复，拒绝。</li>
     *     <li>< 0：顺序倒退，拒绝。</li>
     * </ol>
     *
     * @param other 待比较的候选键
     * @return 负数表示当前键排在对方之前，正数表示排在之后，零表示创建时间和完整范围均相同
     * @throws NullPointerException 对方候选键为 {@code null} 时抛出
     */
    @Override
    public int compareTo(WorkspaceCandidateKey other) {
        int time = other.createdAt.compareTo(createdAt);
        if (time != 0) return time;
        int tenant = scope.tenantId().compareTo(other.scope.tenantId());
        return tenant != 0 ? tenant : scope.workspaceId().compareTo(other.scope.workspaceId());
    }
}
