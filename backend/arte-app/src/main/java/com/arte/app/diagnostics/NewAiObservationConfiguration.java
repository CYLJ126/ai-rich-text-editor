package com.arte.app.diagnostics;

import com.alibaba.druid.pool.DruidDataSource;
import com.arte.core.diagnostics.ObservedScheduler;
import com.arte.core.diagnostics.RequestObservation;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import reactor.core.scheduler.Scheduler;

/**
 * 新 AI HTTP 请求诊断配置，统一接入请求、数据库和调度器指标。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 09:41 ✾
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte.observability.ai-http", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NewAiObservationConfiguration {

    @PostConstruct
    void install() {
        RequestObservation.installReactorPropagation();
    }

    @PreDestroy
    void remove() {
        RequestObservation.removeReactorPropagation();
    }

    /**
     * 注册新 AI 请求观察过滤器，在认证前开始计时，覆盖异步及错误派发。
     */
    @Bean
    FilterRegistrationBean<NewAiHttpObservationFilter> newAiHttpObservationFilter() {
        var registration = new FilterRegistrationBean<>(new NewAiHttpObservationFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST, jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR);
        registration.addUrlPatterns("/ai-new/*");
        return registration;
    }

    /**
     * 为应用数据源添加 JDBC 指标过滤器，并为持久化调度器接入排队指标与 trace 传播。
     * 保留 DataSource 本身的身份，避免破坏现有事务管理器的资源绑定。
     */
    @Bean
    static BeanPostProcessor newAiObservationInfrastructure() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String name) {
                if ("appDataSource".equals(name) && bean instanceof DruidDataSource source) {
                    if (source.getProxyFilters().stream().noneMatch(NewAiJdbcObservationFilter.class::isInstance))
                        source.getProxyFilters().add(new NewAiJdbcObservationFilter());
                }
                if (bean instanceof Scheduler scheduler && ("newAiPersistenceScheduler".equals(name) || "platformPersistenceScheduler".equals(name)))
                    return new ObservedScheduler(scheduler, name);
                return bean;
            }
        };
    }
}
