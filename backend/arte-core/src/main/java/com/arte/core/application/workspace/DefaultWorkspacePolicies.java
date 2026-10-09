package com.arte.core.application.workspace;

import com.arte.core.enums.access.GrantTargetKindEnum;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.PolicyRules;
import com.arte.core.pojo.workspace.TenantPolicy;
import com.arte.core.pojo.workspace.WorkspacePolicy;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.arte.core.constant.platform.PlatformResourceTypes.*;

/**
 * 首版结构化政策模板。生产环境显式传入受支持的协议／供应商／目的地白名单。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
public final class DefaultWorkspacePolicies {

    private final PolicyRules personalRules;

    public DefaultWorkspacePolicies(PolicyRules personalRules) {
        this.personalRules = Objects.requireNonNull(personalRules);
    }

    /**
     * 未配置连接白名单时保持为空，不能把空集合解释为任意外发目的地。
     */
    public static DefaultWorkspacePolicies conservativePersonalDefaults() {
        return new DefaultWorkspacePolicies(new PolicyRules(Set.of(ARTICLE, COLLECTION, MODEL, ASSISTANT),
                Map.of(), false, true, Set.of(), Set.of(), Set.of(), Set.of(ARTICLE, COLLECTION),
                Set.of(ARTICLE, COLLECTION), Set.of(GrantTargetKindEnum.SUBJECT,
                GrantTargetKindEnum.SPACE_ROLE, GrantTargetKindEnum.WORKSPACE)));
    }

    public TenantPolicy personalTenant(String tenantId) {
        return new TenantPolicy(tenantId, personalRules, false, 1, 1, 0);
    }

    public WorkspacePolicy workspace(Scope scope, TenantPolicy tenantPolicy) {
        return new WorkspacePolicy(scope, tenantPolicy.rules(), tenantPolicy.policySchemaVersion(),
                tenantPolicy.rolePresetVersion(), 0);
    }
}
