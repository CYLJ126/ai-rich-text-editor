package com.nip.ai;

import com.nip.ai.config.AiProperties;
import com.nip.core.factory.YamlPropertySourceFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/**
 * @author zhangsc
 * @since 2025/5/29 20:22
 */
@Configuration
@PropertySource(value = "classpath:application-ai.yml", factory = YamlPropertySourceFactory.class)
@ComponentScan(basePackages = "com.nip.ai")
@EnableConfigurationProperties(AiProperties.class)
public class AiAutoConfiguration {
}
