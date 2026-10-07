package com.arte.app.ainew;

import com.arte.ainew.web.controller.NewAiBudgetController;
import com.arte.ainew.web.controller.NewAiChatController;
import com.arte.ainew.web.controller.NewAiConversationController;
import com.arte.ainew.web.controller.NewAiInvocationController;
import com.arte.app.AIRichTextEditorApplication;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证真实启动类的扫描范围与开关条件；不启动数据库、Worker 或模型调用。
 */
public class NewAiWebScanningTest {

    private Set<String> discoverControllers(boolean enabled, boolean executionEnabled) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("web-scan-test", Map.of(
                "arte.ai-new.enabled", enabled,
                "arte.ai-new-execution.enabled", executionEnabled)));
        var scanner = new ClassPathScanningCandidateComponentProvider(false, environment);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var controllers = new HashSet<String>();
        var packages = AIRichTextEditorApplication.class.getAnnotation(ComponentScan.class).basePackages();
        for (var basePackage : packages) {
            for (var candidate : scanner.findCandidateComponents(basePackage)) {
                var name = candidate.getBeanClassName();
                if (name != null && name.startsWith("com.arte.ainew.web.controller.")) {
                    controllers.add(name);
                }
            }
        }
        return controllers;
    }

    @Test
    public void applicationScanDiscoversAllEnabledNewAiControllers() {
        assertEquals(Set.of(NewAiConversationController.class.getName(), NewAiChatController.class.getName(),
                        NewAiBudgetController.class.getName(), NewAiInvocationController.class.getName()),
                discoverControllers(true, true));
    }

    @Test
    public void disabledNewAiDoesNotExposeControllers() {
        assertEquals(Set.of(), discoverControllers(false, true));
    }

    @Test
    public void invocationRequiresExecutionButConversationChatAndBudgetRemainAvailable() {
        assertEquals(Set.of(NewAiConversationController.class.getName(), NewAiChatController.class.getName(),
                NewAiBudgetController.class.getName()), discoverControllers(true, false));
    }
}
