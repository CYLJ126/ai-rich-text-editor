package com.arte.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 定义于 base 节点下的属性
 *
 * @author zhangsc
 * @since 2025/5/29 19:36
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "base")
public class AiProperties {
}
