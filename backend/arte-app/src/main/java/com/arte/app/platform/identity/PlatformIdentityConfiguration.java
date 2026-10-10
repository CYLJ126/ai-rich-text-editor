package com.arte.app.platform.identity;

import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.scheduler.Scheduler;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;

/**
 * 身份映射显式启用；不执行 DDL、不替换现有登录或 AI 默认授权 Bean。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 13:35 ✾
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte.platform.identity", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PlatformIdentityProperties.class)
public class PlatformIdentityConfiguration {

    @Bean
    JdbcPrincipalIdentityStore principalIdentityStore(@Qualifier("appDataSource") DataSource dataSource, RbacAccountReader accounts) {
        return new JdbcPrincipalIdentityStore(dataSource, accounts);
    }

    @Bean
    RbacIdentityProvider platformIdentityProvider(@Qualifier("appDataSource") DataSource dataSource, RbacAccountReader accounts,
                                                  JdbcPrincipalIdentityStore identities, PlatformIdentityProperties properties,
                                                  ObjectProvider<NewAiProperties> aiProperties) {
        // 不注册第二个默认事务管理器，避免改变旧 @Transactional 的选取行为。
        var template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return new RbacIdentityProvider(accounts, identities, template, properties,
                () -> {
                    var ai = aiProperties.getIfAvailable();
                    return ai == null ? List.of() : ai.grants();
                }, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(prefix = "arte.ai-new", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(ExecutionAuthorizationResolver.class)
    ExecutionAuthorizationResolver rbacFixedExecutionAuthorizationResolver(RbacIdentityProvider identities, NewAiProperties properties,
                                                                           @Qualifier("newAiPersistenceScheduler") Scheduler scheduler) {
        return new RbacFixedExecutionAuthorizationResolver(identities, properties, scheduler);
    }
}
