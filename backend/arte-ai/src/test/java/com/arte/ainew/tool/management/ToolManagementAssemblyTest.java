package com.arte.ainew.tool.management;

import com.arte.ainew.application.tool.management.ToolManagementService;
import com.arte.ainew.config.NewAiToolManagementConfiguration;
import com.arte.ainew.spi.auth.ToolManagementAccess;
import com.arte.ainew.web.controller.NewAiToolController;
import org.junit.Test;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.util.*;

import static org.junit.Assert.*;

/**
 * 开关关闭时没有管理路由／依赖；开启时没有宿主授权不能以空实现启动。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
public class ToolManagementAssemblyTest {

    @Test
    public void bothSwitchesAreRequiredWithoutDependenciesOrStartupSideEffects() {
        for (var flags : List.of(Map.<String, Object>of(), Map.<String, Object>of("arte.ai-new.enabled", true), Map.<String, Object>of("arte.ai-new-tool.enabled", true))) {
            try (var c = new AnnotationConfigApplicationContext()) {
                c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", flags));
                c.register(NewAiToolController.class, NewAiToolManagementConfiguration.class);
                c.refresh();
                assertTrue(c.getBeansOfType(NewAiToolController.class).isEmpty());
                assertTrue(c.getBeansOfType(ToolManagementService.class).isEmpty());
            }
        }
    }

    @Test
    public void enabledControllerWithoutHostAuthorizationFailsClosed() {
        try (var c = context()) {
            c.register(NewAiToolController.class);
            assertThrows(BeansException.class, c::refresh);
        }
    }

    @Test
    public void assemblyDoesNotAuthenticateReadSourcesOrWriteSql() {
        try (var c = context()) {
            c.registerBean(ToolManagementAccess.class, () -> auth -> {
                throw new AssertionError("No startup authentication");
            });
            c.register(NewAiToolController.class);
            c.refresh();
            assertNotNull(c.getBean(NewAiToolController.class));
        }
    }

    @Test
    public void everyRouteRequiresAuthenticationAndOnlyImplementedManagementRoutesExist() {
        assertEquals("isAuthenticated()", AnnotatedElementUtils.findMergedAnnotation(NewAiToolController.class, PreAuthorize.class).value());
        var routes = Arrays.stream(NewAiToolController.class.getDeclaredMethods()).map(m -> m.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class))
                .filter(Objects::nonNull).flatMap(a -> Arrays.stream(a.value())).collect(java.util.stream.Collectors.toSet());
        assertEquals(16, routes.size());
        assertTrue(routes.contains("/refreshLocalCatalog"));
        for (var future : List.of("/invokeTool", "/ensureDefaultBinding", "/resolveToolSet", "/setProviderState", "/syncProvider"))
            assertFalse(routes.contains(future));
    }

    private AnnotationConfigApplicationContext context() {
        var c = new AnnotationConfigApplicationContext();
        c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of("arte.ai-new.enabled", true, "arte.ai-new-tool.enabled", true)));
        c.registerBean(ToolManagementService.class, () -> new ToolManagementService(null, null, null, null, null, null, null, null));
        return c;
    }
}
