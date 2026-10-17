package com.arte.ainew.config;

import com.arte.ainew.api.conversation.ConversationSearchService;
import com.arte.ainew.application.search.DefaultConversationSearchService;
import com.arte.ainew.application.search.SearchReadAuthorization;
import com.arte.ainew.infrastructure.search.RedisConversationSearchSessions;
import com.arte.ainew.persistence.mybatis.MybatisConversationSearchReads;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import com.arte.ainew.spi.conversation.ConversationArchiveReader;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.search.ConversationSearchIndex;
import com.arte.ainew.spi.search.ConversationSearchReads;
import com.arte.ainew.spi.search.ConversationSearchSessions;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * 查询独立灰度；协调线程与 ES／执行持久化线程分开，避免同步等待同一线程池。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
@AutoConfiguration(after = NewAiSearchConfiguration.class)
@ConditionalOnProperty(name = {"arte.ai-new.enabled", "arte.ai-new-search.enabled", "arte.ai-new-search.query-enabled"}, havingValue = "true")
public class NewAiSearchQueryConfiguration {

    @Bean(name = "newAiSearchApplicationScheduler", destroyMethod = "dispose")
    Scheduler applicationScheduler(NewAiSearchProperties p) {
        return Schedulers.newBoundedElastic(p.limits().ioThreads(), p.limits().queuedTasks(), "arte-search-query");
    }

    @Bean(name = "newAiSearchCleanupScheduler", destroyMethod = "dispose")
    Scheduler cleanupScheduler() {
        return Schedulers.newBoundedElastic(1, 1000, "arte-search-cleanup");
    }

    @Bean
    @ConditionalOnMissingBean(ConversationSearchSessions.class)
    ConversationSearchSessions searchSessions(RedissonClient redis, NewAiSearchProperties p) {
        return new RedisConversationSearchSessions(redis, p);
    }

    @Bean
    @ConditionalOnMissingBean(ConversationSearchReads.class)
    ConversationSearchReads searchReads(NewAiProperties p, ListableBeanFactory beans, ExecutionRecordCodec codec, ConversationArchiveReader archives) {
        return new MybatisConversationSearchReads(beans.getBean(p.dataSourceBean(), DataSource.class), codec, archives);
    }

    @Bean
    @ConditionalOnMissingBean(com.arte.ainew.spi.conversation.ConversationContentVisibility.class)
    com.arte.ainew.spi.conversation.ConversationContentVisibility searchContentVisibility(ConversationSearchReads reads,
                                                                                          @Qualifier("newAiSearchApplicationScheduler") Scheduler scheduler) {
        return (owner, invocations) -> reactor.core.publisher.Mono.fromCallable(() -> reads.readableInvocations(owner, invocations)).subscribeOn(scheduler);
    }

    @Bean
    @ConditionalOnMissingBean(ConversationSearchService.class)
    ConversationSearchService searchService(ConversationSearchIndex index, ConversationSearchSessions sessions, ConversationSearchReads reads,
                                            ExecutionAuthorizationResolver resolver, NewAiSearchProperties p, @Qualifier("newAiClock") Clock clock,
                                            @Qualifier("newAiSearchApplicationScheduler") Scheduler scheduler, @Qualifier("newAiSearchCleanupScheduler") Scheduler cleanup) {
        return new DefaultConversationSearchService(index, sessions, reads, new SearchReadAuthorization(resolver, clock), p, scheduler, cleanup, clock);
    }
}
