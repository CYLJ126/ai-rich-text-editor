package com.arte.ainew.infrastructure.control;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.control.AiEntitlement;

/**
 * 固定配置的模型使用资格适配
 * <p>
 * 未迁移范围保留绑定 ID 授权；已迁移范围通过公共 ACL 判定，不修改固定引用或执行归属。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 13:00 ✾
 */
public final class FixedAiResourceEntitlementService extends ConfigurationAiResourceEntitlementService {

    public FixedAiResourceEntitlementService(NewAiProperties properties, AdmissionAuthorization authorization) {
        super(new ControlConfigurationReader(properties), authorization, AiEntitlement.Source.FIXED);
    }

    public FixedAiResourceEntitlementService(NewAiProperties properties, AdmissionAuthorization authorization,
                                             com.arte.ainew.spi.model.ModelAclAccess access) {
        super(new ControlConfigurationReader(properties), authorization, AiEntitlement.Source.FIXED, access);
    }

}
