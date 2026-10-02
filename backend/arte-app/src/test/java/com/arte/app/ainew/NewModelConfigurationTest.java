package com.arte.app.ainew;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewModelConfigurationTest {
    @Test
    void newModelBeansAndRoutesRemainAbsentUntilExplicitlyEnabled() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(NewModelConfiguration.class, NewModelController.class);
            context.refresh();
            assertTrue(context.getBeansOfType(NewModelCallService.class).isEmpty());
            assertTrue(context.getBeansOfType(NewModelController.class).isEmpty());
        }
    }

    @Test
    void enablingWithoutSecurityAndExecutionSupportFailsInsteadOfImplicitlyAllowing() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("model", Map.of("arte.ai-new.model.enabled", "true")));
            context.register(NewModelConfiguration.class);
            assertThrows(RuntimeException.class, context::refresh);
        }
    }
}
