package com.arte.core.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 异步任务线程池配置
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 13:28 ✾
 **/
@Slf4j
@EnableAsync
@Configuration
public class AiAsyncConfig {
    /**
     * AI 异步任务线程池
     * 用于消息持久化、摘要生成等后台任务
     */
    @Bean("aiTaskExecutor")
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("ai-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("[AiAsyncConfig] AI异步线程池初始化完成");
        return executor;
    }
}
