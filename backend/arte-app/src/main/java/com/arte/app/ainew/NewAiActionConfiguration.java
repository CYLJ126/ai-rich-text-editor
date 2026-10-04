package com.arte.app.ainew;

import com.arte.ai.api.action.AiActionService;
import com.arte.ai.api.context.ResourceContextService;
import com.arte.ai.api.execution.InvocationCoordinator;
import com.arte.app.security.bridge.EgressConsentService;
import com.arte.app.security.bridge.ExecutionContextFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;

/**
 * 动作独立开关；启用时要求新模型及身份基础设施和动作表，不依赖聊天表。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "arte.ai-new.action.enabled", havingValue = "true")
@org.springframework.context.annotation.Import(NewResourceContextConfiguration.class)
public class NewAiActionConfiguration {
    @Bean
    public JdbcAiActionStore newAiActionStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcAiActionStore(jdbc, manager);
    }

    @Bean
    public AiActionService newAiActions(JdbcAiActionStore store, InvocationCoordinator coordinator, ConfiguredModelDefinitions definitions,
                                        @Value("${arte.ai-new.model.max-input-bytes:16384}") int bytes,
                                        @Value("${arte.ai-new.model.max-output-tokens:2048}") int tokens,
                                        @Value("${arte.ai-new.chat.context-window-tokens:8192}") int window,
                                        @Value("${arte.ai-new.chat.context-safety-tokens:256}") int safety,
                                        @Value("${arte.ai-new.chat.streaming-enabled:true}") boolean streaming, ResourceContextService resourceContexts) {
        return new AiActionService(store, coordinator, definitions.capabilityRef(), definitions.bindingRef(), Clock.systemUTC(),
                bytes, tokens, window, safety, streaming, resourceContexts);
    }

    @Bean
    public NewAiActionCallService newAiActionCalls(AiActionService actions, ExecutionContextFactory contexts, EgressConsentService consents,
                                                   ConfiguredModelDefinitions definitions, PlatformTransactionManager manager,
                                                   @Value("${arte.ai-new.model.application-id:ai-new-model}") String application) {
        return new NewAiActionCallService(actions, contexts, consents, definitions, application, manager);
    }

    @Bean(destroyMethod = "close")
    public AiActionEventStreams newAiActionEventStreams(NewAiActionCallService service, JdbcModelExecutionStore ledger,
                                                        @Value("${arte.ai-new.chat.stream-max-clients:64}") int capacity,
                                                        @Value("${arte.ai-new.chat.stream-timeout:PT2M}") String timeout) {
        return new AiActionEventStreams(service, capacity, Duration.parse(timeout), ledger);
    }
}
