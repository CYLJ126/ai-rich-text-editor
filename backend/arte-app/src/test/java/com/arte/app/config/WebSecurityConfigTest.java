package com.arte.app.config;

import com.arte.ainew.web.controller.NewAiBudgetController;
import com.arte.ainew.web.controller.NewAiChatController;
import com.arte.ainew.web.controller.NewAiConversationController;
import com.arte.ainew.web.controller.NewAiInvocationController;
import com.arte.core.annotations.AnonymousAccess;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.util.pattern.PathPatternParser;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证真实 URL 分类逻辑；无需启动数据库、JWT 服务或模型。
 */
class WebSecurityConfigTest {
    private final WebSecurityConfig config = new WebSecurityConfig();

    private RequestMappingInfo mapping(String path) {
        var options = new RequestMappingInfo.BuilderConfiguration();
        options.setPatternParser(new PathPatternParser());
        return RequestMappingInfo.paths(path).options(options).build();
    }

    private Map<RequestMappingInfo, HandlerMethod> endpoint(Object controller) throws Exception {
        return Map.of(mapping("/test-endpoint"), new HandlerMethod(controller, controller.getClass().getMethod("endpoint")));
    }

    private Set<String> urls(String collector, Map<RequestMappingInfo, HandlerMethod> handlers) {
        String[] result = ReflectionTestUtils.invokeMethod(config, collector, handlers);
        assertNotNull(result);
        return Set.copyOf(Arrays.asList(result));
    }

    @Test
    void classAuthorizationAllowsAuthenticatedRoutingWithoutAnonymousAccess() throws Exception {
        var handlers = endpoint(new ClassSecuredController());
        assertTrue(urls("getUnControlUrls", handlers).isEmpty());
        assertTrue(urls("getAnonymousUrls", handlers).isEmpty());
    }

    @Test
    void methodAuthorizationStillAllowsAuthenticatedRouting() throws Exception {
        var handlers = endpoint(new MethodSecuredController());
        assertTrue(urls("getUnControlUrls", handlers).isEmpty());
        assertTrue(urls("getAnonymousUrls", handlers).isEmpty());
    }

    @Test
    void endpointsWithoutAccessDeclarationsRemainDenied() throws Exception {
        assertEquals(Set.of("/test-endpoint"), urls("getUnControlUrls", endpoint(new UnsecuredController())));
    }

    @Test
    void explicitMethodAnonymousAccessRemainsAnonymous() throws Exception {
        var handlers = endpoint(new AnonymousController());
        assertTrue(urls("getUnControlUrls", handlers).isEmpty());
        assertEquals(Set.of("/test-endpoint"), urls("getAnonymousUrls", handlers));
    }

    @Test
    void inheritedClassAuthorizationIsRecognized() throws Exception {
        assertTrue(urls("getUnControlUrls", endpoint(new InheritedController())).isEmpty());
    }

    @Test
    void composedClassAuthorizationIsRecognized() throws Exception {
        assertTrue(urls("getUnControlUrls", endpoint(new ComposedController())).isEmpty());
    }

    @Test
    void allNewAiEndpointsAreProtectedRatherThanDeniedOrAnonymous() {
        var handlers = new LinkedHashMap<RequestMappingInfo, HandlerMethod>();
        var beans = new DefaultListableBeanFactory();
        for (var controller : Set.of(NewAiConversationController.class, NewAiChatController.class,
                NewAiInvocationController.class, NewAiBudgetController.class)) {
            var beanName = controller.getSimpleName();
            beans.registerBeanDefinition(beanName, new RootBeanDefinition(controller));
            var root = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            assertNotNull(root);
            for (var method : controller.getDeclaredMethods()) {
                var post = AnnotatedElementUtils.findMergedAnnotation(method, PostMapping.class);
                if (post != null) {
                    handlers.put(mapping(root.value()[0] + post.value()[0]), new HandlerMethod(beanName, beans, method));
                }
            }
        }
        assertEquals(10, handlers.size());
        assertTrue(urls("getUnControlUrls", handlers).isEmpty());
        assertTrue(urls("getAnonymousUrls", handlers).isEmpty());
    }

    @PreAuthorize("hasAuthority('chat:read')")
    public static class ClassSecuredController {
        public void endpoint() {
        }
    }

    public static class MethodSecuredController {
        @PreAuthorize("isAuthenticated()")
        public void endpoint() {
        }
    }

    public static class UnsecuredController {
        public void endpoint() {
        }
    }

    @PreAuthorize("isAuthenticated()")
    public static class AnonymousController {
        @AnonymousAccess
        public void endpoint() {
        }
    }

    public static class InheritedController extends ClassSecuredController {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @PreAuthorize("isAuthenticated()")
    public @interface SecuredController {
    }

    @SecuredController
    public static class ComposedController {
        public void endpoint() {
        }
    }
}
