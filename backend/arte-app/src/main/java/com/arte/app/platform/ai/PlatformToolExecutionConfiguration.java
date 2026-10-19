package com.arte.app.platform.ai;

import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.app.platform.ai.tool.LocalTextTools;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 正式应用的本地文本工具与执行授权装配；不开启功能开关，不自动发布、建绑或授予角色。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 11:18 ✾
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-tool.enabled"}, havingValue = "true")
public class PlatformToolExecutionConfiguration {
    @Bean
    LocalTextTools localTextTools(@Qualifier("newAiClock") Clock clock) {
        return new LocalTextTools(clock);
    }

    @Bean
    LocalToolRegistry localToolRegistry(LocalTextTools tools) {
        return tools.registry();
    }

    @Bean
    PlatformToolExecutionAccess toolExecutionAccess(PlatformToolManagementAccess platform, LocalTextTools tools) {
        return new PlatformToolExecutionAccess(platform, tools);
    }
}
