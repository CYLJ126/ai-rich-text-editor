package com.arte.app.platform.ai;

import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.spi.auth.ToolInvocationAccess;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.identity.RbacToolPermissionReader;
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
 * TOOL 新调用关闭后，仍提供已有记录的当前读取／控制资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 20:47 ✾
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-execution.enabled", "platform.identity.enabled",
        "platform.persistence.enabled", "platform.workspace.enabled"}, havingValue = "true")
public class PlatformToolInvocationConfiguration {

    /**
     * 历史及停止资格不依赖执行端口；工具执行关闭后仍可对已有记录收尾。
     */
    @Bean
    ToolInvocationAccess toolInvocationAccess(RbacIdentityProvider identities,
                                              @Qualifier("appDataSource") DataSource source,
                                              @Qualifier("platformTransactionRunner") PublicTransactionRunner transactions,
                                              WorkspaceWriteGuard guard, NewAiProperties properties,
                                              @Qualifier("newAiPersistenceScheduler") Scheduler scheduler, @Qualifier("newAiClock") Clock clock,
                                              Environment environment) {
        for (var module : java.util.List.of("identity", "persistence", "workspace"))
            if (!environment.getProperty("arte.platform." + module + ".enabled", Boolean.class, false))
                throw new IllegalStateException("Tool invocation access requires public " + module);
        if (!"appDataSource".equals(properties.dataSourceBean()))
            throw new IllegalStateException("Tool invocation access requires appDataSource");
        return new PlatformToolInvocationAccess(identities, new RbacToolPermissionReader(source), transactions, guard, properties,
                "DATABASE".equals(environment.getProperty("arte.ai-new.configuration-mode", "FIXED")), scheduler, clock);
    }

}
