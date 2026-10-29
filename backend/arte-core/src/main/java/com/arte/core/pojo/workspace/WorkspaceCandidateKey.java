package com.arte.core.pojo.workspace;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformTimePrecision;

import java.time.Instant;
import java.util.Objects;

/**
 * 空间目录分页扫描的稳定排序键
 * <p>
 * 内部候选扫描的稳定键（不可变排序键）：创建时间降序、租户 ID 与空间 ID 升序。
 * 创建时间和完整范围在创建后不可变；更新、改名和成员状态不参与游标排序（不判断记录有没有被修改）。
 * 用于空间目录分批扫描时定位严格向后的续查边界，即用于定位下一批候选；候选空间仍须逐项检查当前访问资格。
 *
 * @param createdAt 候选空间的创建时间，不能为空且必须已为微秒精度；按降序排列，作为创建后不变的第一排序项。
 * @param scope     候选空间的完整租户与空间标识，不能为空且创建后不变；创建时间相同时按租户 ID、空间 ID 的字典序升序排列，确定唯一的扫描位置。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 12:10 ✾
 */
public record WorkspaceCandidateKey(Instant createdAt, Scope scope) implements Comparable<WorkspaceCandidateKey> {

    public WorkspaceCandidateKey {
        PlatformTimePrecision.requireMicros(createdAt);
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
