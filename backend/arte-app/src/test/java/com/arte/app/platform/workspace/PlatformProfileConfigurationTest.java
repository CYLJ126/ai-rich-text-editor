package com.arte.app.platform.workspace;

import com.arte.app.platform.identity.PlatformIdentityConfiguration;
import com.arte.app.platform.identity.PlatformIdentityProperties;
import com.arte.app.platform.persistence.PlatformPersistenceConfiguration;
import com.arte.app.platform.persistence.PlatformPersistenceProperties;
import com.arte.app.web.controller.platform.WorkspaceAccessController;
import com.arte.app.web.controller.platform.WorkspaceController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the Maven-filtered runtime file through the application's real config import.
 */
class PlatformProfileConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            // Test resources also contain application.yml; select the filtered production file explicitly.
            .withPropertyValues("spring.config.location=" + PlatformWorkspaceConfiguration.class.getProtectionDomain()
                    .getCodeSource().getLocation().toExternalForm() + "application.yml,classpath:application-ai.yml");

    @Test
    void disabledPlatformImportsConfigurationWithoutRequiringDatabaseOrReviewedIdentity() {
        runner.withPropertyValues("arte.platform.identity.enabled=false", "arte.platform.persistence.enabled=false",
                        "arte.platform.workspace.enabled=false")
                .withUserConfiguration(PlatformIdentityConfiguration.class, PlatformPersistenceConfiguration.class,
                        PlatformWorkspaceConfiguration.class, WorkspaceController.class, WorkspaceAccessController.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getEnvironment().getPropertySources().stream()
                            .anyMatch(source -> source.getName().contains("platform/config/application.properties")));
                    assertNotNull(context.getEnvironment().getProperty("arte.platform.persistence.workers", Integer.class));
                    assertEquals(context.getEnvironment().getProperty("arte.ai-new.grants[0].subject-id"),
                            context.getEnvironment().getProperty("arte.platform.identity.bindings[0].subject-id"));
                    assertTrue(context.getBeansOfType(WorkspaceController.class).isEmpty());
                    assertTrue(context.getBeansOfType(PlatformIdentityProperties.class).isEmpty());
                });
    }

    @Test
    void reviewedTestOverridesBindAllLimitsPoliciesAndTheOriginalFixedSubject() {
        runner.withUserConfiguration(BindingConfiguration.class)
                .withPropertyValues("arte.platform.identity.enabled=true", "arte.platform.persistence.enabled=true",
                        "arte.platform.workspace.enabled=true", "arte.platform.identity.legacy-user-id-ceiling=123",
                        "arte.platform.identity.bindings[0].user-id=42",
                        // Spring replaces a list per property source; keep both binding fields in the test override.
                        "arte.platform.identity.bindings[0].subject-id=${arte.ai-new.grants[0].subject-id}")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var identity = context.getBean(PlatformIdentityProperties.class);
                    assertEquals(123L, identity.legacyUserIdCeiling());
                    assertEquals(42L, identity.bindings().getFirst().userId());
                    assertEquals(context.getEnvironment().getProperty("arte.ai-new.grants[0].subject-id"),
                            identity.bindings().getFirst().subjectId());
                    var persistence = context.getBean(PlatformPersistenceProperties.class);
                    assertTrue(persistence.workers() > 0);
                    assertTrue(persistence.transactionTimeoutSeconds() >= persistence.queryTimeoutSeconds());
                    assertFalse(persistence.cursorTtl().isNegative());
                    assertTrue(context.getBean(PlatformWorkspaceProperties.class).enabled());
                    assertNull(context.getEnvironment().getProperty("arte.platform.workspace.personal-policy.creation-types"));
                });
    }

    @Test
    void enablingIdentityWithoutReviewedFieldsFailsInsteadOfInventingAnAccountMapping() {
        runner.withUserConfiguration(BindingConfiguration.class)
                .withPropertyValues("arte.platform.identity.enabled=true",
                        "arte.platform.identity.legacy-user-id-ceiling=", "arte.platform.identity.bindings[0].user-id=")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({PlatformIdentityProperties.class, PlatformPersistenceProperties.class,
            PlatformWorkspaceProperties.class})
    static class BindingConfiguration {
    }
}
