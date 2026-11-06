package com.arte.core.pojo.workspace;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 空间政策权威
 * <p>
 * 必须在租户上限内，更新与 workspaceEpoch 同事务提交。
 *
 * @param scope               政策所属的空间范围
 * @param rules               该空间配置的政策规则
 * @param policySchemaVersion 政策数据结构版本
 * @param rolePresetVersion   政策采用的角色预设版本
 * @param version             空间政策记录版本
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record WorkspacePolicy(
        Scope scope,
        PolicyRules rules,
        int policySchemaVersion,
        int rolePresetVersion,
        long version) {

    public WorkspacePolicy {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(rules, "rules");
        PlatformChecks.require(policySchemaVersion >= 1, "policySchemaVersion must be positive");
        PlatformChecks.require(rolePresetVersion >= 1, "rolePresetVersion must be positive");
        PlatformChecks.version(version, "version");
    }
}
