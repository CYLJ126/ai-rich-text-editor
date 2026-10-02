package com.arte.app.ainew;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewChatConfigurationTest {
    @Test
    void chatBeansAndRoutesRemainAbsentUntilExplicitlyEnabled() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(NewChatConfiguration.class, NewChatController.class);
            context.refresh();
            assertTrue(context.getBeansOfType(NewChatCallService.class).isEmpty());
            assertTrue(context.getBeansOfType(NewChatController.class).isEmpty());
        }
    }

    @Test
    void enablingWithoutModelAndIdentityInfrastructureFails() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("chat", Map.of("arte.ai-new.chat.enabled", "true")));
            context.register(NewChatConfiguration.class);
            assertThrows(RuntimeException.class, context::refresh);
        }
    }
}
