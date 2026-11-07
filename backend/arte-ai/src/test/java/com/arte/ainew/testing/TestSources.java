package com.arte.ainew.testing;

import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Standalone assembly tests install trusted ports explicitly; no deployment definitions are bound.
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 11:25 ✾
 */
public final class TestSources {

    public static void install(AnnotationConfigApplicationContext context) {
        install(context, com.arte.ainew.admission.AdmissionFixture.properties());
    }

    public static void install(AnnotationConfigApplicationContext context, TestAiConfiguration data) {
        if (context.getBeanFactory().getBeanNamesForType(AiControlConfigurationSource.class, false, false).length == 0) {
            context.registerBean("testControlSource", AiControlConfigurationSource.class, () -> data.source());
        }
        if (context.getBeanFactory().getBeanNamesForType(ExecutionAuthorizationResolver.class, false, false).length == 0) {
            context.registerBean("testAuthorizationResolver", ExecutionAuthorizationResolver.class, () -> new TestAuthorizationResolver(data));
        }
    }
}
