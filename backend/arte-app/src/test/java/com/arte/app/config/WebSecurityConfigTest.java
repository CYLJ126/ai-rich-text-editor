package com.arte.app.config;

import com.arte.app.ainew.NewChatBootstrapController;
import com.arte.app.ainew.NewChatBootstrapService;
import com.arte.app.ainew.NewChatCallService;
import com.arte.app.ainew.NewChatController;
import com.arte.app.api.rbac.OnlineService;
import com.arte.app.api.rbac.TokenService;
import com.arte.app.config.bean.WebSecurityProperties;
import com.arte.app.web.filter.AuthenticationTokenFilter;
import com.arte.core.annotations.AnonymousAccess;
import com.arte.core.pojo.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WebSecurityConfigTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestWeb.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        UserContext.clear();
        if (context != null) context.close();
    }

    @Test
    void authenticatedUserCanReachClassSecuredChatEndpoints() throws Exception {
        mvc.perform(get("/api/ai-new/chat/bootstrap").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/ai-new/conversations").header("Authorization", "Bearer admin")
                        .param("tenantId", "tenant").param("workspaceId", "workspace"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(context.getBean(NewChatCallService.class)).list(any(), eq("tenant"), eq("workspace"),
                isNull(), eq(0), eq(50));
    }

    @Test
    void anonymousUserStillCannotReachClassSecuredChatEndpoints() throws Exception {
        mvc.perform(get("/api/ai-new/chat/bootstrap"))
                .andExpect(content().string("ARTE Authentication failed\n"));
        mvc.perform(get("/api/ai-new/conversations").param("tenantId", "tenant").param("workspaceId", "workspace"))
                .andExpect(content().string("ARTE Authentication failed\n"));
        verifyNoInteractions(context.getBean(NewChatCallService.class));
    }

    @Test
    void undeclaredEndpointsRemainDeniedAndExistingMethodAnnotationsStillWork() throws Exception {
        mvc.perform(get("/security-fixture/undeclared").header("Authorization", "Bearer admin"))
                .andExpect(content().string("ARTE Access denied\n"));
        mvc.perform(get("/security-fixture/secured").header("Authorization", "Bearer admin"))
                .andExpect(content().string("secured"));
        mvc.perform(get("/security-fixture/anonymous"))
                .andExpect(content().string("anonymous"));
    }

    @Test
    void methodRestrictionStillOverridesClassAuthentication() throws Exception {
        mvc.perform(get("/class-security-fixture/restricted").header("Authorization", "Bearer admin"))
                .andExpect(content().string("ARTE Access denied\n"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(WebSecurityConfig.class)
    static class TestWeb {
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
        TokenService tokenService() {
            var tokens = mock(TokenService.class);
            when(tokens.verifyToken("admin")).thenReturn(true);
            when(tokens.getUserDetailsByToken("admin")).thenReturn(User.withUsername("admin")
                    .password("unused").roles("ADMIN").build());
            return tokens;
        }

        @Bean
        OnlineService onlineService() {
            return mock(OnlineService.class);
        }

        @Bean
        NewChatBootstrapController bootstrapController(ObjectProvider<NewChatBootstrapService> services) {
            return new NewChatBootstrapController(services);
        }

        @Bean
        NewChatCallService chatService() {
            var service = mock(NewChatCallService.class);
            when(service.list(any(), anyString(), anyString(), any(), anyInt(), anyInt())).thenReturn(List.of());
            return service;
        }

        @Bean
        NewChatController chatController(NewChatCallService service) {
            return new NewChatController(service);
        }

        @Bean
        MethodSecurityFixture methodSecurityFixture() {
            return new MethodSecurityFixture();
        }

        @Bean
        ClassSecurityFixture classSecurityFixture() {
            return new ClassSecurityFixture();
        }
    }

    @RestController
    static class MethodSecurityFixture {
        @GetMapping("/security-fixture/undeclared")
        public String undeclared() {
            return "undeclared";
        }

        @PreAuthorize("isAuthenticated()")
        @GetMapping("/security-fixture/secured")
        public String secured() {
            return "secured";
        }

        @AnonymousAccess
        @GetMapping("/security-fixture/anonymous")
        public String anonymous() {
            return "anonymous";
        }
    }

    @RestController
    @PreAuthorize("isAuthenticated()")
    static class ClassSecurityFixture {
        @PreAuthorize("denyAll()")
        @GetMapping("/class-security-fixture/restricted")
        public String restricted() {
            return "restricted";
        }
    }
}
