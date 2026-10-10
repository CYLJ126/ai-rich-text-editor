package com.arte.app.platform.identity;

import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import com.arte.core.spi.platform.IdentityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

class PlatformIdentityConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Database.class, PlatformIdentityConfiguration.class);

    @Test
    void disabledIdentityDoesNotRegisterMappingOrChangeAiResolver() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(IdentityProvider.class).isEmpty());
            assertTrue(context.getBeansOfType(ExecutionAuthorizationResolver.class).isEmpty());
        });
    }

    @Test
    void enabledIdentityAssemblesWithoutCreatingTablesOrReplacingTransactionManager() {
        runner.withPropertyValues("arte.platform.identity.enabled=true", "arte.platform.identity.legacy-user-id-ceiling=0")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertInstanceOf(RbacIdentityProvider.class, context.getBean(IdentityProvider.class));
                    assertTrue(context.getBeansOfType(org.springframework.transaction.PlatformTransactionManager.class).isEmpty());
                });
    }

    @Test
    void configurationBindsFullLongAccountIdsWithoutTruncation() {
        runner.withPropertyValues("arte.platform.identity.enabled=true",
                        "arte.platform.identity.legacy-user-id-ceiling=" + Long.MAX_VALUE,
                        "arte.platform.identity.bindings[0].user-id=" + Long.MAX_VALUE,
                        "arte.platform.identity.bindings[0].subject-id=reviewed-large-subject")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var properties = context.getBean(PlatformIdentityProperties.class);
                    assertEquals(Long.MAX_VALUE, properties.legacyUserIdCeiling());
                    assertEquals(Long.MAX_VALUE, properties.bindings().getFirst().userId());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({RbacAccountReader.class, RbacAccountMapperConfiguration.class})
    static class Database {
        @Bean(name = "appDataSource")
        DataSource source() {
            return new DriverManagerDataSource("jdbc:h2:mem:identityConfiguration", "sa", "");
        }
    }

}
