package com.arte.core.pojo.workspace;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 受信部署显式提供的组织初始政策，来源版本随创建事件记录。
 *
 * @param version 组织初始政策的来源模板版本
 * @param rules 新组织租户采用的初始政策规则
 * @param membersMayCreateWorkspace 是否允许普通租户成员创建空间
 * @param policySchemaVersion 政策数据结构版本
 * @param rolePresetVersion 政策采用的角色预设版本
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 17:11 ✾
 */
public record OrganizationTenantPolicyTemplate(long version, PolicyRules rules, Boolean membersMayCreateWorkspace,
                                               int policySchemaVersion, int rolePresetVersion) {

    public OrganizationTenantPolicyTemplate {
        PlatformChecks.require(version > 0 && policySchemaVersion == 1 && rolePresetVersion == 1,
                "Unsupported organization policy template");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(membersMayCreateWorkspace, "membersMayCreateWorkspace");
    }

    public TenantPolicy initialPolicy(String tenantId) {
        return new TenantPolicy(tenantId, rules, membersMayCreateWorkspace, policySchemaVersion, rolePresetVersion, 0);
    }
}
