package com.arte.ainew.testing;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import com.arte.ainew.spi.auth.ToolInvocationAccess;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;

/**
 * 为独立业务测试装配受信内存来源，不安装固定配置运行时。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 11:25 ✾
 */
public final class TestAuthorizations {

    public static AdmissionAuthorization create(ExecutionAuthorizationResolver resolver, TestAiConfiguration data, Clock clock) {
        return create(resolver, data, clock, data.source(resolver), Schedulers.immediate(), null);
    }

    public static AdmissionAuthorization create(ExecutionAuthorizationResolver resolver, TestAiConfiguration data, Clock clock, AiControlConfigurationSource source, Scheduler scheduler) {
        return create(resolver, data, clock, source, scheduler, null);
    }

    public static AdmissionAuthorization create(ExecutionAuthorizationResolver resolver, TestAiConfiguration data, Clock clock, AiControlConfigurationSource source, Scheduler scheduler, ToolInvocationAccess tools) {
        return new AdmissionAuthorization(resolver, clock, source == null ? data.source() : source, scheduler == null ? Schedulers.immediate() : scheduler, tools);
    }
}
