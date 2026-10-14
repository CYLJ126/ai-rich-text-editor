package com.arte.app.platform.persistence;

import com.arte.app.platform.persistence.mapper.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;

/**
 * 平台 Mapper 固定使用 appDataSource，不经过路由库或旧创建人过滤／审计改写。
 * 为平台 ServiceImpl 提供专用 Mapper，不自动建表，不注册新的默认事务管理器。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte.platform.persistence", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PlatformPersistenceProperties.class)
public class PlatformMybatisConfiguration {

    @Bean
    PlatformMybatisSession platformMapperSession(
            @Qualifier("appDataSource") DataSource source, PlatformPersistenceProperties properties) {
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("platform-app",
                new SpringManagedTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultEnumTypeHandler(MybatisEnumTypeHandler.class);
        configuration.setCacheEnabled(false);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setDefaultExecutorType(ExecutorType.SIMPLE);
        configuration.setDefaultStatementTimeout(properties.queryTimeoutSeconds());
        configuration.setDatabaseId(new org.apache.ibatis.mapping.VendorDatabaseIdProvider().getDatabaseId(source));
        // 显式注册，不使用 com.arte.app.mapper 扫描，不添加旧业务或自动版本更新拦截器。
        // CAS、成员代次及授权 epoch 的约束由 Mapper XML 的专用 SQL 明确表达。
        for (Class<?> mapper : List.of(PlatformTenantMapper.class, PlatformWorkspaceMapper.class,
                PlatformTenantMemberMapper.class, PlatformWorkspaceMemberMapper.class,
                PlatformTenantPolicyMapper.class, PlatformWorkspacePolicyMapper.class, PlatformPersonalPolicyTemplateMapper.class, PlatformAiControlMapper.class,
                PlatformWorkspacePreferenceMapper.class, PlatformCommandMapper.class,
                PlatformOutboxMapper.class, PlatformAuditMapper.class)) {
            configuration.addMapper(mapper);
        }
        return new PlatformMybatisSession(new MybatisSqlSessionFactoryBuilder().build(configuration));
    }

    @Bean
    PlatformPersonalPolicyTemplateMapper platformPersonalPolicyTemplateMapper(PlatformMybatisSession session) {
        return session.mapper(PlatformPersonalPolicyTemplateMapper.class);
    }

    @Bean
    public PlatformAiControlMapper platformAiControlMapper(PlatformMybatisSession session) {
        return session.mapper(PlatformAiControlMapper.class);
    }

    @Bean
    PlatformTenantMapper platformTenantMapper(PlatformMybatisSession session) {
        return session.mapper(PlatformTenantMapper.class);
    }

    @Bean
    PlatformWorkspaceMapper platformWorkspaceMapper(PlatformMybatisSession session) {
        return session.mapper(PlatformWorkspaceMapper.class);
    }

    @Bean
    PlatformTenantMemberMapper platformTenantMemberMapper(PlatformMybatisSession session) {
        return session.mapper(PlatformTenantMemberMapper.class);
    }

    @Bean
    PlatformWorkspaceMemberMapper platformWorkspaceMemberMapper(PlatformMybatisSession session) {
        return session.mapper(PlatformWorkspaceMemberMapper.class);
    }

    @Bean
    PlatformTenantPolicyMapper platformTenantPolicyMapper(PlatformMybatisSession session) {
        return session.mapper(PlatformTenantPolicyMapper.class);
    }

    @Bean
    PlatformWorkspacePolicyMapper platformWorkspacePolicyMapper(PlatformMybatisSession session) {
        return session.mapper(PlatformWorkspacePolicyMapper.class);
    }

    @Bean
    PlatformWorkspacePreferenceMapper platformWorkspacePreferenceMapper(PlatformMybatisSession session) {
        return session.mapper(PlatformWorkspacePreferenceMapper.class);
    }

    @Bean
    PlatformCommandMapper platformCommandMapper(PlatformMybatisSession session) {
        return session.mapper(PlatformCommandMapper.class);
    }

    @Bean
    PlatformOutboxMapper platformOutboxMapper(PlatformMybatisSession session) {
        return session.mapper(PlatformOutboxMapper.class);
    }

    @Bean
    PlatformAuditMapper platformAuditMapper(PlatformMybatisSession session) {
        return session.mapper(PlatformAuditMapper.class);
    }
}
