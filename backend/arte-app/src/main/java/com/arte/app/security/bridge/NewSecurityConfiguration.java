package com.arte.app.security.bridge;

import com.arte.app.api.rbac.TokenService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;

/**
 * 新入口单独启用；旧过滤器、权限校验和 AI Bean 不替换。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "arte.security.bridge.enabled", havingValue = "true")
public class NewSecurityConfiguration {
    @Bean
    public JdbcSecurityRepository newSecurityRepository(JdbcTemplate jdbc) {
        return new JdbcSecurityRepository(jdbc);
    }

    @Bean
    public ExistingIdentityAdapter existingIdentityAdapter(TokenService tokens, JdbcSecurityRepository repository) {
        return new ExistingIdentityAdapter(tokens, repository);
    }

    @Bean
    public ExecutionContextFactory executionContextFactory(ExistingIdentityAdapter identity, JdbcSecurityRepository repository,
                                                           @Value("${arte.security.bridge.task-lifetime:PT30M}") Duration taskLifetime) {
        return new ExecutionContextFactory(identity, repository, Clock.systemUTC(), taskLifetime);
    }

    @Bean
    public ExistingShareQueries existingShareQueries(JdbcTemplate jdbc, SqlSessionFactory mybatis) {
        return new ExistingShareQueries(jdbc, mybatis);
    }

    @Bean
    public LegacyResourcePermissions legacyResourcePermissions(JdbcTemplate jdbc, ExistingShareQueries shares, JdbcSecurityRepository repository) {
        return new LegacyResourcePermissions(jdbc, shares, repository);
    }

    @Bean
    public ExistingAuthorizationService existingAuthorizationService(JdbcSecurityRepository repository, LegacyResourcePermissions resources) {
        return new ExistingAuthorizationService(repository, resources, Clock.systemUTC());
    }

    @Bean
    public ExistingEgressPolicy existingEgressPolicy(JdbcSecurityRepository repository, ExistingAuthorizationService authorization) {
        return new ExistingEgressPolicy(repository, authorization, Clock.systemUTC());
    }

    @Bean
    public EgressConsentService egressConsentService(ExistingIdentityAdapter identity, JdbcSecurityRepository repository, ExistingEgressPolicy egress) {
        return new EgressConsentService(identity, repository, egress, Clock.systemUTC());
    }
}
