package com.arte.core.application.workspace;

import com.arte.core.enums.access.GrantTargetKindEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.PersonalTenantPolicyTemplate;
import com.arte.core.pojo.workspace.PolicyRules;
import com.arte.core.pojo.workspace.TenantPolicy;
import com.arte.core.pojo.workspace.WorkspacePolicy;
import com.arte.core.spi.workspace.PersonalTenantPolicyTemplateStore;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static com.arte.core.constant.platform.PlatformResourceTypes.*;

/**
 * 新个人租户政策工厂；生产环境通过版本化数据库模板提供政策。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
public final class DefaultWorkspacePolicies {

    private final Supplier<PersonalTenantPolicyTemplate> personalTemplate;

    public DefaultWorkspacePolicies(PolicyRules personalRules) {
        Objects.requireNonNull(personalRules);
        this.personalTemplate = () -> new PersonalTenantPolicyTemplate(personalRules, false, 1, 1, 1);
    }

    /**
     * 生产装配每次新建时读取数据库；不在启动时缓存模板。
     */
    public DefaultWorkspacePolicies(PersonalTenantPolicyTemplateStore store) {
        Objects.requireNonNull(store);
        this.personalTemplate = () -> {
            try {
                return store.findCurrent().orElseThrow(() -> new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, "personal-policy-template"));
            } catch (PlatformException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                var unavailable = new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, "personal-policy-template");
                unavailable.initCause(failure);
                throw unavailable;
            }
        };
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
        var template = personalTemplate.get();
        WorkspaceAccessSupport.requirePolicyVersion(template.policySchemaVersion(), template.rolePresetVersion());
        return template.instantiate(tenantId);
    }

    public WorkspacePolicy workspace(Scope scope, TenantPolicy tenantPolicy) {
        return new WorkspacePolicy(scope, tenantPolicy.rules(), tenantPolicy.policySchemaVersion(),
                tenantPolicy.rolePresetVersion(), 0);
    }
}
