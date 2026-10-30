package com.arte.ainew.infrastructure.control;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import reactor.core.scheduler.Scheduler;

/**
 * 数据库控制面模型资格适配
 * <p>
 * 复用来源核验的当前成员、空间策略和精确版本白名单。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 13:00 ✾
 */
public final class SourceAiResourceEntitlementService extends ConfigurationAiResourceEntitlementService {

    public SourceAiResourceEntitlementService(AiControlConfigurationSource source, Scheduler scheduler, AdmissionAuthorization authorization) {
        super(new ControlConfigurationReader(source, scheduler), authorization, AiEntitlement.Source.DATABASE);
    }
}
