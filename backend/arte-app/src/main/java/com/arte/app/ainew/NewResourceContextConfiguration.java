package com.arte.app.ainew;

import com.arte.ai.api.context.ResourceContextService;
import com.arte.ai.spi.business.ResourceContextAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/** 动作和 RAG 共用一个来源授权注册表。由各自配置显式导入。 */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${arte.ai-new.action.enabled:false} or ${arte.ai-new.chat.enabled:false}")
public class NewResourceContextConfiguration {
    @Bean
    public ResourceContextService newResourceContexts(ObjectProvider<ResourceContextAdapter> adapters,
                                                       @Value("${arte.ai-new.model.max-input-bytes:16384}") int bytes,
                                                       @Value("${arte.ai-new.chat.context-window-tokens:8192}") int window,
                                                       @Value("${arte.ai-new.chat.context-safety-tokens:256}") int safety,
                                                       @Value("${arte.ai-new.chat.snapshot-ttl:PT10M}") String ttl) {
        return new ResourceContextService(adapters.orderedStream().toList(), Clock.systemUTC(), Duration.parse(ttl), bytes, window, safety);
    }
}
