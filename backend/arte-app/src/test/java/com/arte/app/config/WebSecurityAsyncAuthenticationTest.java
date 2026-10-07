package com.arte.app.config;

import com.arte.app.api.rbac.OnlineService;
import com.arte.app.api.rbac.TokenService;
import com.arte.app.config.bean.WebSecurityProperties;
import com.arte.app.web.filter.AuthenticationTokenFilter;
import com.arte.core.pojo.UserContext;
import com.arte.core.pojo.UserOnlineInfo;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import reactor.core.publisher.Mono;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 真实 SecurityFilterChain + JWT 过滤器 + MVC Mono 异步分发；身份服务使用内存替身。
 */
class WebSecurityAsyncAuthenticationTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private Calls calls;

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        calls = context.getBean(Calls.class);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        UserContext.clear();
        if (context != null) context.close();
    }

    @Test
    void authenticatedMonoResponseRetainsIdentityAcrossAsyncDispatchWithoutSession() throws Exception {
        var pending = mvc.perform(post("/secured-async/result")
                .header("Authorization", "Bearer valid-token")).andReturn();
        assertTrue(pending.getRequest().isAsyncStarted());
        pending.getAsyncResult(5000);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(pending.getRequest().getSession(false));
        SecurityContextHolder.clearContext();
        // ASYNC must restore the saved request context rather than revalidate JWT.
        pending.getRequest().removeHeader("Authorization");
        var completed = mvc.perform(asyncDispatch(pending)).andReturn();
        assertEquals(DispatcherType.ASYNC, completed.getRequest().getDispatcherType());
        assertEquals("authenticated-result", completed.getResponse().getContentAsString());
        assertNull(completed.getRequest().getSession(false));
        assertEquals(1, calls.verifications.get());
        assertEquals(1, calls.renewals.get());
        assertEquals(1, calls.controller.get());
    }

    @Test
    void missingOrInvalidTokenCannotReachProtectedController() throws Exception {
        var missing = mvc.perform(post("/secured-async/result")).andReturn();
        assertFalse(missing.getRequest().isAsyncStarted());
        assertTrue(missing.getResponse().getContentAsString().contains("ARTE Authentication failed"));
        var invalid = mvc.perform(post("/secured-async/result")
                .header("Authorization", "Bearer invalid-token")).andReturn();
        assertFalse(invalid.getRequest().isAsyncStarted());
        assertTrue(invalid.getResponse().getContentAsString().contains("ARTE Authentication failed"));
        assertEquals(0, calls.controller.get());
    }

    @Test
    void authenticationDoesNotCarryOverToAnotherRequest() throws Exception {
        var pending = mvc.perform(post("/secured-async/result")
                .header("Authorization", "Bearer valid-token")).andReturn();
        pending.getAsyncResult(5000);
        var completed = mvc.perform(asyncDispatch(pending)).andReturn();
        assertEquals("authenticated-result", completed.getResponse().getContentAsString());
        var next = mvc.perform(post("/secured-async/result")).andReturn();
        assertFalse(next.getRequest().isAsyncStarted());
        assertTrue(next.getResponse().getContentAsString().contains("ARTE Authentication failed"));
        assertNull(next.getRequest().getSession(false));
        assertEquals(1, calls.controller.get());
    }

    @Test
    void asyncDispatchWithoutSavedAuthenticationIsStillDenied() throws Exception {
        var result = mvc.perform(post("/secured-async/result").with(request -> {
            request.setDispatcherType(DispatcherType.ASYNC);
            return request;
        })).andReturn();
        assertTrue(result.getResponse().getContentAsString().contains("ARTE Authentication failed"));
        assertEquals(0, calls.controller.get());
    }

    @Test
    void methodAuthorizationStillEnforcesAuthorities() throws Exception {
        var result = mvc.perform(post("/secured-async/restricted")
                .header("Authorization", "Bearer valid-token")).andReturn();
        assertTrue(result.getResponse().getContentAsString().contains("ARTE Access denied"));
        assertEquals(0, calls.controller.get());
    }

    static final class Calls {
        final AtomicInteger verifications = new AtomicInteger();
        final AtomicInteger renewals = new AtomicInteger();
        final AtomicInteger controller = new AtomicInteger();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(WebSecurityConfig.class)
    static class TestConfiguration {
        @Bean
        Calls calls() {
            return new Calls();
        }

        @Bean
        AuthenticationTokenFilter authenticationTokenFilter() {
            return new AuthenticationTokenFilter();
        }

        @Bean
        WebSecurityProperties webSecurityProperties() {
            var properties = new WebSecurityProperties();
            properties.setHeader("Authorization");
            properties.setTokenStartWith("Bearer");
            return properties;
        }

        @Bean
        TokenService tokenService(Calls calls) {
            return stub(TokenService.class, (proxy, method, arguments) -> switch (method.getName()) {
                case "verifyToken" -> {
                    calls.verifications.incrementAndGet();
                    yield "valid-token".equals(arguments[0]);
                }
                case "getUserDetailsByToken" -> User.withUsername("tester").password("unused").roles("USER").build();
                case "renewal" -> {
                    calls.renewals.incrementAndGet();
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        @Bean
        OnlineService onlineService() {
            return stub(OnlineService.class, (proxy, method, arguments) -> {
                if (method.getName().equals("getOnlineInfo")) return new UserOnlineInfo().setUserName("tester");
                throw new UnsupportedOperationException(method.getName());
            });
        }

        @Bean
        AsyncController asyncController(Calls calls) {
            return new AsyncController(calls);
        }

        private static <T> T stub(Class<T> type, InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> "test-" + type.getSimpleName();
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                }
                return handler.invoke(proxy, method, arguments);
            }));
        }
    }

    @RestController
    @RequestMapping("/secured-async")
    @PreAuthorize("isAuthenticated()")
    public static class AsyncController {
        private final Calls calls;

        AsyncController(Calls calls) {
            this.calls = calls;
        }

        @PostMapping("/result")
        public Mono<String> result() {
            calls.controller.incrementAndGet();
            return Mono.delay(Duration.ofMillis(10)).map(ignored -> "authenticated-result");
        }

        @PostMapping("/restricted")
        @PreAuthorize("hasAuthority('SPECIAL')")
        public Mono<String> restricted() {
            calls.controller.incrementAndGet();
            return Mono.just("must-not-be-returned");
        }
    }
}
