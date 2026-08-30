package com.arte.app.config;

import cn.hutool.core.util.ObjectUtil;
import com.arte.app.web.filter.AuthenticationTokenFilter;
import com.arte.core.annotations.AnonymousAccess;
import jakarta.annotation.Resource;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 认证相关配置
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/4 17:43 ✾
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class WebSecurityConfig {

    @Resource
    private AuthenticationTokenFilter authenticationTokenFilter;

    @Resource
    private ApplicationContext applicationContext;

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        // 当访问接口没有权限时回调
        return (request, response, accessDeniedException) -> {
            logSecurityFailure("ARTE Access denied", request, accessDeniedException);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            // 或使用 403 错误返回  response.sendError(HttpServletResponse.SC_FORBIDDEN, accessDeniedException.getMessage());
            response.getWriter().println("ARTE Access denied");
            response.getWriter().flush();
        };
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        // 未认证时回调，也就是说没有登录
        return (request, response, authException) -> {
            logSecurityFailure("Unauthenticated", request, authException);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            // 或使用 401 错误返回 response.sendError(HttpServletResponse.SC_UNAUTHORIZED, authException==null?"Unauthorized":authException.getMessage());
            response.getWriter().println("ARTE Authentication failed");
            response.getWriter().flush();
        };
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {
        RequestMappingHandlerMapping requestMappingHandlerMapping = (RequestMappingHandlerMapping) applicationContext.getBean("requestMappingHandlerMapping");
        Map<RequestMappingInfo, HandlerMethod> handlerMethodMap = requestMappingHandlerMapping.getHandlerMethods();
        //由于使用的是JWT，这里不需要csrf防护
        httpSecurity.csrf(CsrfConfigurer::disable)
                //基于token，所以不需要session
                .sessionManagement(sessionManagementConfigurer -> sessionManagementConfigurer.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorizationRegistry -> authorizationRegistry
                        // 容器会将 4xx/5xx 请求内部分发到 /error，错误分发不应再触发鉴权
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        //允许对于网站静态资源的无授权访问
                        .requestMatchers(HttpMethod.GET, "/", "/*.html").permitAll()
                        // 本地开放的文件访问接口
                        .requestMatchers(HttpMethod.GET,
                                "/article-covers/**",
                                "/richText/file/local/image/**",
                                "/richText/file/local/file/**").permitAll()
                        //对登录注册、MCP 调用，允许匿名访问
                        .requestMatchers("/auth/login", "/webSocket/**", "/mcp", "/mcp/**", "/user/register", "/test/**").permitAll()
                        // 放行OPTIONS请求，跨域请求会先进行一次options请求
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 自定义匿名访问所有url放行
                        .requestMatchers(getAnonymousUrls(handlerMethodMap)).permitAll()
                        .requestMatchers(getUnControlUrls(handlerMethodMap)).denyAll()
                        // 除上面外的所有请求全部需要鉴权认证
                        .anyRequest().authenticated()
                )
                //禁用缓存
                .headers(headersConfigurer -> headersConfigurer
                        .cacheControl(HeadersConfigurer.CacheControlConfig::disable)
                        // 允许同源页面通过 object/iframe 展示 LOCAL 模式下的 SVG 等资源
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
                )
                //使用自定义provider
//                .authenticationProvider(authenticationProvider)
                //添加JWT filter
                .addFilterBefore(authenticationTokenFilter, UsernamePasswordAuthenticationFilter.class)
                //添加自定义未授权和未登录结果返回
                .exceptionHandling(exceptionConfigurer -> exceptionConfigurer
                        .accessDeniedHandler(accessDeniedHandler())
                        .authenticationEntryPoint(authenticationEntryPoint()));
        return httpSecurity.build();
    }

    private void logSecurityFailure(String message, HttpServletRequest request, Exception exception) {
        log.error("{}：method={}, dispatcherType={}, requestUri={}, queryString={}, " +
                        "originalRequestUri={}, errorStatus={}, errorMessage={}, originalException={}",
                message,
                request.getMethod(),
                request.getDispatcherType(),
                request.getRequestURI(),
                request.getQueryString(),
                request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI),
                request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE),
                request.getAttribute(RequestDispatcher.ERROR_MESSAGE),
                request.getAttribute(RequestDispatcher.ERROR_EXCEPTION),
                exception);
    }

    /**
     * 搜寻匿名标记，参见 {@link AnonymousAccess}
     *
     * @return 可匿名访问的 url 列表
     */
    private String[] getAnonymousUrls(Map<RequestMappingInfo, HandlerMethod> handlerMethodMap) {
        // 搜寻匿名标记 url： @AnonymousAccess
        List<String> all = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> infoEntry : handlerMethodMap.entrySet()) {
            HandlerMethod handlerMethod = infoEntry.getValue();
            AnonymousAccess anonymousAccess = handlerMethod.getMethodAnnotation(AnonymousAccess.class);
            if (null != anonymousAccess) {
                all.addAll(getPatterns(infoEntry.getKey()));
                all.addAll(getPathPatterns(infoEntry.getKey()));
            }
        }
        return all.toArray(new String[0]);
    }

    /**
     * 搜寻未注解 @AnonymousAccess 或 @PreAuthorize 的 url
     *
     * @return 禁止访问的 url 列表
     */
    private String[] getUnControlUrls(Map<RequestMappingInfo, HandlerMethod> handlerMethodMap) {
        // 搜寻未注解 @AnonymousAccess 或 @PreAuthorize 的 url，禁止访问
        List<String> all = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> infoEntry : handlerMethodMap.entrySet()) {
            HandlerMethod handlerMethod = infoEntry.getValue();
            AnonymousAccess anonymousAccess = handlerMethod.getMethodAnnotation(AnonymousAccess.class);
            PreAuthorize preAuthorize = handlerMethod.getMethodAnnotation(PreAuthorize.class);
            if (ObjectUtil.isAllEmpty(anonymousAccess, preAuthorize)) {
                all.addAll(getPatterns(infoEntry.getKey()));
                all.addAll(getPathPatterns(infoEntry.getKey()));
            }
        }
        return all.toArray(new String[0]);
    }

    private Collection<String> getPatterns(RequestMappingInfo requestMappingInfo) {
        return Optional.ofNullable(requestMappingInfo.getPathPatternsCondition())
                .map(condition -> condition.getPatterns().stream()
                        .map(PathPattern::getPatternString)
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());
    }

    private Collection<String> getPathPatterns(RequestMappingInfo requestMappingInfo) {
        return Optional.ofNullable(requestMappingInfo.getPathPatternsCondition())
                .map(PathPatternsRequestCondition::getPatterns)
                .map(patterns -> patterns.stream()
                        .map(PathPattern::getPatternString)
                        .toList())
                .orElse(Collections.emptyList());
    }
}
