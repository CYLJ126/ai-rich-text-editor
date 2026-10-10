package com.arte.app.platform.persistence;

import com.arte.app.service.platform.AuditSinkServiceImpl;
import com.arte.app.service.platform.TenantStoreServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class PlatformPersistenceConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = AuditSinkServiceImpl.class)
    static class AuditServices {
    }

    @Test
    void disabledByDefaultDoesNotRegisterOrRequireRedisAndIdentity() {
        new ApplicationContextRunner()
                .withUserConfiguration(PlatformPersistenceConfiguration.class, AuditServices.class)
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            assertEquals(0, context.getBeansOfType(TenantStoreServiceImpl.class).size());
                            assertEquals(0, context.getBeansOfType(com.arte.core.spi.platform.AuditSink.class).size());
                        });
    }

    @Test
    void enabledInfrastructureRegistersStoresWithoutReplacingDefaultManager() {
        new ApplicationContextRunner()
                .withUserConfiguration(PlatformPersistenceConfiguration.class, AuditServices.class)
                .withPropertyValues("arte.platform.persistence.enabled=true")
                .withBean(
                        "appDataSource",
                        javax.sql.DataSource.class,
                        () ->
                                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                        "jdbc:h2:mem:configuration", "sa", ""))
                .withBean(
                        com.arte.core.spi.platform.IdentityProvider.class,
                        () ->
                                org.mockito.Mockito.mock(
                                        com.arte.core.spi.platform.IdentityProvider.class))
                .withBean(
                        org.redisson.api.RedissonClient.class,
                        () -> org.mockito.Mockito.mock(org.redisson.api.RedissonClient.class))
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            for (Class<?> spi : java.util.List.of(
                                    com.arte.core.spi.workspace.TenantStore.class,
                                    com.arte.core.spi.workspace.WorkspaceStore.class,
                                    com.arte.core.spi.workspace.TenantMembershipStore.class,
                                    com.arte.core.spi.workspace.WorkspaceMembershipStore.class,
                                    com.arte.core.spi.workspace.PreferenceStore.class,
                                    com.arte.core.spi.workspace.PolicyStore.class,
                                    com.arte.core.spi.workspace.WorkspaceAccessFactsStore.class,
                                    com.arte.core.spi.platform.CommandReceiptStore.class,
                                    com.arte.core.spi.platform.PublicOutboxStore.class,
                                    com.arte.app.api.platform.OutboxDeliveryService.class)) {
                                assertEquals(1, context.getBeansOfType(spi).size(), spi.getName());
                            }
                            assertEquals(1, context.getBeansOfType(com.arte.core.spi.platform.AuditSink.class).size());
                            assertSame(context.getBean("auditSinkService"),
                                    context.getBean(com.arte.core.spi.platform.AuditSink.class));
                            assertSame(context.getBean("platformTransactionRunner"),
                                    ReflectionTestUtils.getField(context.getBean("auditSinkService"), "tx"));
                            assertSame(context.getBean("platformAuditMapper"),
                                    context.getBean(AuditSinkServiceImpl.class).getBaseMapper());
                            assertInstanceOf(com.arte.app.service.platform.AuditSinkServiceImpl.class,
                                    context.getBean(com.arte.core.spi.platform.AuditSink.class));
                            assertEquals(
                                    1,
                                    context.getBeansOfType(
                                                    com.arte.core.spi.workspace.TenantStore.class)
                                            .size());
                            assertEquals(
                                    1,
                                    context.getBeansOfType(
                                                    com.arte.core.spi.platform
                                                            .PublicTransactionRunner.class)
                                            .size());
                            assertEquals(
                                    0,
                                    context.getBeansOfType(
                                                    org.springframework.transaction
                                                            .PlatformTransactionManager.class)
                                            .size());
                            assertEquals(
                                    0, context.getBeansOfType(RedisOutboxPublisher.class).size());
                            assertEquals(
                                    0,
                                    context.getBeansOfType(
                                                    com.arte.core.api.workspace.WorkspaceService
                                                            .class)
                                            .size());
                        });
    }

    @Test
    void explicitlyDisabledDoesNotRegisterScannedAuditService() {
        new ApplicationContextRunner()
                .withUserConfiguration(PlatformPersistenceConfiguration.class, AuditServices.class)
                .withPropertyValues("arte.platform.persistence.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("auditSinkService"));
                    assertEquals(0, context.getBeansOfType(com.arte.core.spi.platform.AuditSink.class).size());
                });
    }

    @Test
    void rejectsUnsafeLimits() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PlatformPersistenceProperties(
                                true, -1, 1, 5, 15, 10, null, null, null, null, null, false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PlatformPersistenceProperties(
                                true, 1, 1, 10, 5, 10, null, null, null, null, null, false));
    }
}
