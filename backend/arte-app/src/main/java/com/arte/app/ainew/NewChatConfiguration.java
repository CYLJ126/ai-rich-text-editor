package com.arte.app.ainew;

import com.arte.ai.api.context.ContextService;
import com.arte.ai.api.conversation.ChatService;
import com.arte.ai.api.conversation.ConversationService;
import com.arte.ai.api.execution.InvocationCoordinator;
import com.arte.ai.spi.security.ChatAccessPolicy;
import com.arte.app.security.bridge.EgressConsentService;
import com.arte.app.security.bridge.ExecutionContextFactory;
import com.arte.app.security.bridge.ExistingIdentityAdapter;
import com.arte.app.security.bridge.JdbcSecurityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;

/**
 * 独立聊天开关默认关闭；显式启用时要求模型、身份及执行支撑已配置。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "arte.ai-new.chat.enabled", havingValue = "true")
public class NewChatConfiguration {
    @Bean
    public NewChatBootstrapService newChatBootstrap(ExistingIdentityAdapter identity, JdbcSecurityRepository repository,
                                                    ConfiguredModelDefinitions definitions,
                                                    @Value("${arte.ai-new.model.application-id:ai-new-model}") String application,
                                                    @Value("${arte.ai-new.model.model-name}") String modelName,
                                                    @Value("${arte.ai-new.chat.context-max-bytes:8192}") int bytes,
                                                    @Value("${arte.ai-new.model.max-output-tokens:2048}") int tokens) {
        return new NewChatBootstrapService(identity, repository, definitions, application, modelName, bytes, tokens);
    }
    @Bean
    public JdbcChatStore newChatStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcChatStore(jdbc, manager);
    }

    @Bean
    public ExistingChatAccessPolicy newChatAccess(JdbcSecurityRepository repository, ConfiguredModelDefinitions definitions,
                                                  @Value("${arte.ai-new.model.application-id:ai-new-model}") String application) {
        return new ExistingChatAccessPolicy(repository, definitions, application, Clock.systemUTC());
    }

    @Bean
    public ConversationService newConversations(JdbcChatStore store, ChatAccessPolicy access) {
        return new ConversationService(store, access, Clock.systemUTC());
    }

    @Bean
    public ContextService newChatContexts(JdbcChatStore store, InvocationCoordinator coordinator,
                                          @Value("${arte.ai-new.chat.context-max-bytes:8192}") int bytes,
                                          @Value("${arte.ai-new.chat.history-pairs:32}") int history,
                                          @Value("${arte.ai-new.chat.snapshot-ttl:PT10M}") String ttl) {
        return new ContextService(store, coordinator, Clock.systemUTC(), bytes, history, Duration.parse(ttl));
    }

    @Bean
    public ChatService newChats(ConversationService conversations, ContextService contexts, JdbcChatStore store,
                                InvocationCoordinator coordinator, ConfiguredModelDefinitions definitions,
                                @Value("${arte.ai-new.model.max-output-tokens:2048}") int tokens) {
        return new ChatService(conversations, contexts, store, coordinator, definitions.capabilityRef(), Clock.systemUTC(), tokens);
    }

    @Bean
    public NewChatCallService newChatCalls(ConversationService conversations, ChatService chats, ExecutionContextFactory contexts,
                                           EgressConsentService consents, ConfiguredModelDefinitions definitions,
                                           @Value("${arte.ai-new.model.application-id:ai-new-model}") String application, PlatformTransactionManager manager) {
        return new NewChatCallService(conversations, chats, contexts, consents, definitions, application, manager);
    }
}
