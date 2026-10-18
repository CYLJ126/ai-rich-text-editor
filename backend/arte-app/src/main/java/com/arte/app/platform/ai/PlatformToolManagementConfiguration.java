package com.arte.app.platform.ai;

import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.spi.auth.ToolManagementAccess;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.identity.RbacToolPermissionReader;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformAiControlMapper;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.spi.access.WorkspaceWriteGuard;
import com.arte.core.spi.platform.PublicTransactionRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import reactor.core.scheduler.Scheduler;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * TOOL 管理必须使用现有公共身份／空间和 app 物理库，缺失基础时启动失败，不注册允许式兜底。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-tool.enabled"}, havingValue = "true")
public class PlatformToolManagementConfiguration {
    @Bean
    ToolManagementAccess toolManagementAccess(RbacIdentityProvider identities, @Qualifier("appDataSource") DataSource source,
                                              @Qualifier("platformTransactionRunner") PublicTransactionRunner transactions,
                                              WorkspaceWriteGuard guard, @Qualifier("platformAuthorizationService") AuthorizationService authorization,
                                              PlatformAiControlMapper configuration, PlatformJson json, NewAiProperties properties,
                                              @Qualifier("newAiPersistenceScheduler") Scheduler scheduler, @Qualifier("newAiClock") Clock clock,
                                              Environment environment) {
        for (var module : java.util.List.of("identity", "persistence", "workspace"))
            if (!environment.getProperty("arte.platform." + module + ".enabled", Boolean.class, false))
                throw new IllegalStateException("Tool management requires public " + module);
        if (!"appDataSource".equals(properties.dataSourceBean()))
            throw new IllegalStateException("Tool management requires appDataSource");
        return new PlatformToolManagementAccess(identities, new RbacToolPermissionReader(source), transactions, guard, authorization,
                configuration, json, properties, "DATABASE".equals(environment.getProperty("arte.ai-new.configuration-mode", "FIXED")), scheduler, clock);
    }
}
