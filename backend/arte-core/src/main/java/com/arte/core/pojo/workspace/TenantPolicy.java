package com.arte.core.pojo.workspace;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 租户政策权威
 * <p>
 * 更新与 tenantEpoch 同事务提交，不能随程序升级隐式扩权。
 *
 * @param tenantId 政策所属的租户 ID
 * @param rules 租户政策规则，作为下属空间的政策上限
 * @param membersMayCreateWorkspace 是否允许普通租户成员创建空间
 * @param policySchemaVersion 政策数据结构版本
 * @param rolePresetVersion 政策采用的角色预设版本
 * @param version 租户政策记录版本
 * @param personalTemplateVersion 创建个人租户时采用的政策模板版本，未记录该来源时为 {@code null}
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record TenantPolicy(
        String tenantId,
        PolicyRules rules,
        boolean membersMayCreateWorkspace,
        int policySchemaVersion,
        int rolePresetVersion,
        long version,
        Long personalTemplateVersion) {

    public TenantPolicy(String tenantId, PolicyRules rules, boolean membersMayCreateWorkspace,
                        int policySchemaVersion, int rolePresetVersion, long version) {
        this(tenantId, rules, membersMayCreateWorkspace, policySchemaVersion, rolePresetVersion, version, null);
    }

    public TenantPolicy {
        PlatformChecks.id(tenantId, "tenantId");
        Objects.requireNonNull(rules, "rules");
        if (personalTemplateVersion != null)
            PlatformChecks.require(personalTemplateVersion >= 1, "personalTemplateVersion must be positive");
        PlatformChecks.require(policySchemaVersion >= 1, "policySchemaVersion must be positive");
        PlatformChecks.require(rolePresetVersion >= 1, "rolePresetVersion must be positive");
        PlatformChecks.version(version, "version");
    }
}
