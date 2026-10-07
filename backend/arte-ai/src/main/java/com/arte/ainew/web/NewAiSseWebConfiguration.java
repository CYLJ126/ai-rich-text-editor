package com.arte.ainew.web;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 的响应式流写入仍是阻塞 Servlet I/O，用有界线程池隔离慢连接。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 00:06 ✾
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = {"arte.ai-new.enabled", "arte.ai-new-execution.enabled"}, havingValue = "true")
public class NewAiSseWebConfiguration {

    /**
     * 创建名为 newAiSseWriteExecutor 的有界线程池，供 MVC 异步处理及响应式 SSE 的阻塞 Servlet 响应写入使用。
     * 核心线程数为 4，最大线程数为 16，待执行任务队列容量为 256；队列容量表示任务数，不是 SSE 连接数上限。
     * 线程名称以 arte-ainew-sse- 开头，便于日志及线程排查；线程池初始化和关闭由 Spring 管理。
     * <p>
     * 随本配置类仅在 Servlet Web 应用且 arte.ai-new.enabled、arte.ai-new-execution.enabled 均为 true 时注册。
     * 通过 newAiSseAsyncSupport 配置到 MVC，数据库操作仍使用存储层原有 Scheduler。
     *
     * @return 由 Spring 管理、限制工作线程数与排队任务数的 MVC 异步执行器。
     */
    @Bean(name = "newAiSseWriteExecutor")
    ThreadPoolTaskExecutor sseWriteExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("arte-ainew-sse-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(256);
        return executor;
    }

    /**
     * 注册 MVC 异步支持配置器，将 newAiSseWriteExecutor 设置为 MVC 的默认异步任务执行器。
     * Spring 在构建 MVC 异步配置时调用 configureAsyncSupport，使响应式 SSE 的阻塞写入使用上述有界线程池。
     * 该设置作用于 MVC 的默认异步执行器，也影响其他使用该默认执行器的异步接口，例如返回 Callable 的控制器方法。
     * <p>
     * 随本配置类在 Servlet Web 应用且两个 AI 启用开关均为 true 时注册；这里只配置执行器，异步超时沿用现有配置。
     *
     * @param executor 通过 Qualifier 指定注入的 newAiSseWriteExecutor Bean，避免使用其他任务执行器。
     * @return 将指定线程池接入 MVC 异步处理的 WebMvcConfigurer。
     */
    @Bean
    WebMvcConfigurer newAiSseAsyncSupport(@Qualifier("newAiSseWriteExecutor") AsyncTaskExecutor executor) {
        return new WebMvcConfigurer() {
            @Override
            public void configureAsyncSupport(@NonNull AsyncSupportConfigurer configurer) {
                // 设置 MVC 默认异步执行器；JDBC 仍使用存储层原有 Scheduler。
                configurer.setTaskExecutor(executor);
            }
        };
    }
}
