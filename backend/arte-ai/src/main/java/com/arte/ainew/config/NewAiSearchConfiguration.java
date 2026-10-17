package com.arte.ainew.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.arte.ainew.api.conversation.ConversationArchiveCommands;
import com.arte.ainew.application.conversation.NoopConversationArchiveReader;
import com.arte.ainew.application.conversation.UnsupportedConversationArchiveCommands;
import com.arte.ainew.infrastructure.search.ConversationSearchBootstrap;
import com.arte.ainew.infrastructure.search.ElasticsearchConversationSearchIndex;
import com.arte.ainew.spi.conversation.ConversationArchiveReader;
import com.arte.ainew.spi.search.ConversationSearchIndex;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 默认仅装配可替换归档端口；只有显式开启初始化才在 ApplicationReady 后访问远端。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
@AutoConfiguration(after = NewAiAdmissionConfiguration.class)
@ConditionalOnProperty(prefix = "arte.ai-new", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(NewAiSearchProperties.class)
public class NewAiSearchConfiguration {
    @Bean
    @ConditionalOnMissingBean(ConversationArchiveReader.class)
    ConversationArchiveReader conversationArchiveReader() {
        return new NoopConversationArchiveReader();
    }

    @Bean
    @ConditionalOnMissingBean(ConversationArchiveCommands.class)
    ConversationArchiveCommands conversationArchiveCommands() {
        return new UnsupportedConversationArchiveCommands();
    }

    @Bean(name = "newAiSearchScheduler", destroyMethod = "dispose")
    @ConditionalOnProperty(prefix = "arte.ai-new-search", name = "enabled", havingValue = "true")
    Scheduler searchScheduler(NewAiSearchProperties p) {
        return Schedulers.newBoundedElastic(p.limits().ioThreads(), p.limits().queuedTasks(), "arte-ainew-search");
    }

    @Bean
    @ConditionalOnMissingBean(ConversationSearchIndex.class)
    @ConditionalOnProperty(prefix = "arte.ai-new-search", name = "enabled", havingValue = "true")
    ConversationSearchIndex conversationSearchIndex(ElasticsearchClient es, RedissonClient requiredRedis,
                                                    NewAiSearchProperties p, @Qualifier("newAiSearchScheduler") Scheduler scheduler,
                                                    ObjectProvider<ConversationSearchBootstrap> bootstrap) {
        // 声明 Redis 为搜索依赖；会话持久化和查询应用服务在第三阶段交付。
        return new ElasticsearchConversationSearchIndex(es, p, scheduler, () -> !p.initializeOnStartup()
                || bootstrap.getIfAvailable() != null && bootstrap.getObject().isReady());
    }
}
