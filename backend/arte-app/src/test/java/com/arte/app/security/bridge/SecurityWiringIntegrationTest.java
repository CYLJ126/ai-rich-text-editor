package com.arte.app.security.bridge;

import com.arte.app.api.rbac.TokenService;
import com.arte.base.api.security.AuthorizationService;
import com.arte.base.api.security.EgressPolicy;
import com.arte.base.model.security.AuthorizationRequest;
import com.arte.base.model.security.CommonResourceAction;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SecurityWiringIntegrationTest extends SecurityBridgeFixture {
    @Test
    void configurationIsOptInAndProvidesBothBasePortsWhenEnabled() {
        try (var disabled = container(false)) {
            assertTrue(disabled.getBeansOfType(AuthorizationService.class).isEmpty());
        }
        try (var enabled = container(true)) {
            assertNotNull(enabled.getBean(AuthorizationService.class));
            assertNotNull(enabled.getBean(EgressPolicy.class));
            assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(enabled.getBean(AuthorizationService.class)));
        }
    }

    @Test
    void taskAndAllActionRowsRollbackTogetherThroughConfiguredSpringBean() {
        policy("resource.read");
        policy("resource.edit");
        jdbc.execute("ALTER TABLE arte_security_task_action ADD CONSTRAINT test_action_failure CHECK (action_code <> 'resource.edit')");
        try (var context = container(true)) {
            var factory = context.getBean(ExecutionContextFactory.class);
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> factory.create(http, TENANT, WORKSPACE, "new-ai", "chat", Set.of("resource.read", "resource.edit")));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_task", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_task_action", Integer.class));
        }
    }

    @Test
    void defaultRequireMethodRunsFreshReadCommittedTransactionInsideOuterTaskTransaction() {
        try (var context = container(true)) {
            policy("resource.read");
            var execution = context.getBean(ExecutionContextFactory.class).create(http, TENANT, WORKSPACE, "new-ai", "chat",
                    Set.of("resource.read"), Map.of(ARTICLE, Set.of("resource.read")));
            var port = context.getBean(AuthorizationService.class);
            var outer = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
            outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            outer.executeWithoutResult(status -> {
                assertNotNull(port.requireAuthorized(AuthorizationRequest.of(execution, ARTICLE, CommonResourceAction.READ)));
                var inner = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
                inner.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                inner.executeWithoutResult(innerStatus -> jdbc.update("UPDATE arte_security_member SET enabled = FALSE, revision = 2"));
                assertThrows(com.arte.base.exception.BaseException.class,
                        () -> port.requireAuthorized(AuthorizationRequest.of(execution, ARTICLE, CommonResourceAction.READ)));
            });
        }
    }

    private AnnotationConfigApplicationContext container(boolean enabled) {
        var context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().setConversionService(org.springframework.boot.convert.ApplicationConversionService.getSharedInstance());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of("arte.security.bridge.enabled", Boolean.toString(enabled))));
        context.registerBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> jdbc);
        context.registerBean(org.apache.ibatis.session.SqlSessionFactory.class, () -> mybatis);
        context.registerBean(TokenService.class, () -> tokens);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(datasource));
        context.register(TestTransactions.class, NewSecurityConfiguration.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestTransactions {
    }
}
