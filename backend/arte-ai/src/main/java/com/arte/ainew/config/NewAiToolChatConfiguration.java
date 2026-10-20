package com.arte.ainew.config;

import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.api.execution.BudgetService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.application.tool.binding.ToolBindingService;
import com.arte.ainew.application.tool.chat.ToolChatBudgetSelector;
import com.arte.ainew.application.tool.chat.ToolChatQueryService;
import com.arte.ainew.persistence.mybatis.MybatisConversationSearchReads;
import com.arte.ainew.persistence.mybatis.MybatisToolChatRunStore;
import com.arte.ainew.spi.conversation.ConversationArchiveReader;
import com.arte.ainew.spi.conversation.ConversationContentVisibility;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ExecutionResultStore;
import com.arte.ainew.spi.persistence.ExecutionStore;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import reactor.core.scheduler.Scheduler;
import javax.sql.DataSource;

import java.time.Clock;

/**
 * S4-03 Run 持久化及只读查询；装配零 SQL，不建表、不启动 Run Worker。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 16:20 ✾
 */
@AutoConfiguration(after = NewAiAdmissionConfiguration.class, before = NewAiSearchQueryConfiguration.class)
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-tool.enabled"}, havingValue = "true")
public class NewAiToolChatConfiguration {

    /**
     * 内容可见性独立于搜索启停；关闭 ES 查询不能让 Run 子调用绕过会话／归档校验。
     */
    @Bean
    @ConditionalOnBean(ConversationService.class)
    @ConditionalOnMissingBean(ConversationContentVisibility.class)
    ConversationContentVisibility toolChatContentVisibility(NewAiProperties properties,
                                                            ListableBeanFactory beans, ExecutionRecordCodec codec,
                                                            ObjectProvider<ConversationArchiveReader> archives,
                                                            @Qualifier("newAiPersistenceScheduler") Scheduler scheduler) {
        var archiveReader = archives.getIfAvailable(() -> (owner, ids) -> reactor.core.publisher.Mono.just(java.util.Set.of()));
        var reads = new MybatisConversationSearchReads(
                beans.getBean(properties.dataSourceBean(), DataSource.class), codec, archiveReader);
        return (owner, invocations) -> reactor.core.publisher.Mono.fromCallable(() -> reads.readableInvocations(owner, invocations)).subscribeOn(scheduler);
    }

    @Bean
    @ConditionalOnBean(ConversationService.class)
    ToolChatQueryService toolChatQueryService(MybatisToolChatRunStore runs,
                                              ConversationService conversations,
                                              AdmissionAuthorization authorization,
                                              ExecutionStore executions, ExecutionResultStore results) {
        return new ToolChatQueryService(runs, conversations, authorization, executions, results);
    }

    @Bean
    @ConditionalOnBean(ConversationService.class)
    ToolChatBudgetSelector toolChatBudgetSelector(MybatisToolChatRunStore runs,
                                                  AdmissionAuthorization authorization, FixedControlCatalog catalog,
                                                  BudgetService budgets,
                                                  ObjectProvider<ToolBindingService> bindings,
                                                  @Qualifier("newAiClock") Clock clock) {
        return new ToolChatBudgetSelector(runs, authorization, catalog, budgets, bindings.getIfAvailable(), clock);
    }

    @Bean
    MybatisToolChatRunStore toolChatRunStore(NewAiProperties properties, ListableBeanFactory beans, ExecutionRecordCodec codec,
                                             @Qualifier("newAiPersistenceScheduler") Scheduler scheduler) {
        return new MybatisToolChatRunStore(beans.getBean(properties.dataSourceBean(), DataSource.class), codec, scheduler);
    }
}
