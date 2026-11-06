package com.arte.core.pojo.workspace;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 新个人租户的不可变政策模板修订；模板版本与租户政策版本相互独立。
 *
 * @param rules                     新个人租户采用的初始政策规则
 * @param membersMayCreateWorkspace 是否允许普通租户成员创建空间
 * @param policySchemaVersion       政策数据结构版本
 * @param rolePresetVersion         政策采用的角色预设版本
 * @param version                   个人租户政策模板的修订版本
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 17:22 ✾
 */
public record PersonalTenantPolicyTemplate(PolicyRules rules, boolean membersMayCreateWorkspace,
                                           int policySchemaVersion, int rolePresetVersion, long version) {
    public PersonalTenantPolicyTemplate {
        Objects.requireNonNull(rules, "rules");
        PlatformChecks.require(policySchemaVersion >= 1 && rolePresetVersion >= 1, "template schema and preset must be positive");
        PlatformChecks.require(version >= 1, "template version must be positive");
    }

    public TenantPolicy instantiate(String tenantId) {
        return new TenantPolicy(tenantId, rules, membersMayCreateWorkspace, policySchemaVersion, rolePresetVersion, 0, version);
    }
}
