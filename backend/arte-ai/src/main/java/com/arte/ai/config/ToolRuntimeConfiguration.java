package com.arte.ai.config;

import com.arte.ai.pojo.tool.ToolClusterIdentity;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 工具运行时配置
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ToolClusterProperties.class, ToolExecutionProperties.class})
public class ToolRuntimeConfiguration {

    @Bean(name = "toolCallbackExecutor", destroyMethod = "close")
    public ExecutorService toolCallbackExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public ToolClusterIdentity toolClusterIdentity(ToolClusterProperties properties) {
        String configuredInstanceId = properties.getInstanceId();
        String instanceId = configuredInstanceId == null || configuredInstanceId.isBlank()
                ? UUID.randomUUID().toString()
                : configuredInstanceId.trim();
        return new ToolClusterIdentity(instanceId);
    }
}
