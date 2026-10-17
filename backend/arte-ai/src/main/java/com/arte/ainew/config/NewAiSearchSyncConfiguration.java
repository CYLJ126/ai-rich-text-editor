package com.arte.ainew.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.arte.ainew.application.search.ConversationSearchMaintenance;
import com.arte.ainew.application.search.ConversationSearchSyncWorker;
import com.arte.ainew.infrastructure.search.ConversationSearchBootstrap;
import com.arte.ainew.infrastructure.search.ElasticsearchConversationSearchWriter;
import com.arte.ainew.persistence.mybatis.JdbcConversationSearchIndexLock;
import com.arte.ainew.persistence.mybatis.MybatisConversationSearchSource;
import com.arte.ainew.persistence.mybatis.MybatisConversationSearchSyncStore;
import com.arte.ainew.spi.conversation.ConversationArchiveReader;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.search.ConversationSearchIndexLock;
import com.arte.ainew.spi.search.ConversationSearchSource;
import com.arte.ainew.spi.search.ConversationSearchSyncStore;
import com.arte.ainew.spi.search.ConversationSearchWriter;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import reactor.core.scheduler.Scheduler;

import javax.sql.DataSource;

/**
 * 初始化和扫描均关闭时不创建存储适配器；构造阶段不访问远端。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
@AutoConfiguration(after = NewAiSearchConfiguration.class)
@ConditionalOnProperty(prefix = "arte.ai-new-search", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "arte.ai-new", name = "enabled", havingValue = "true")
@ConditionalOnExpression("${arte.ai-new-search.initialize-on-startup:false} || ${arte.ai-new-search.scan-enabled:false} || ${arte.ai-new-search.backfill-enabled:false} || ${arte.ai-new-search.reconcile-enabled:false}")
public class NewAiSearchSyncConfiguration {

    @Bean
    @ConditionalOnMissingBean(ConversationSearchIndexLock.class)
    ConversationSearchIndexLock searchIndexLock(NewAiProperties ai, NewAiSearchProperties p, ListableBeanFactory beans) {
        return new JdbcConversationSearchIndexLock(beans.getBean(ai.dataSourceBean(), DataSource.class), p);
    }

    @Bean
    @ConditionalOnMissingBean(ConversationSearchSource.class)
    ConversationSearchSource searchSource(NewAiProperties p, NewAiSearchProperties search, ListableBeanFactory beans, ExecutionRecordCodec codec, ConversationArchiveReader archives) {
        return new MybatisConversationSearchSource(beans.getBean(p.dataSourceBean(), DataSource.class), codec, archives, search.diagnostics());
    }

    @Bean
    @ConditionalOnMissingBean(ConversationSearchSyncStore.class)
    ConversationSearchSyncStore searchSyncStore(NewAiProperties p, NewAiSearchProperties search, ListableBeanFactory beans) {
        return new MybatisConversationSearchSyncStore(beans.getBean(p.dataSourceBean(), DataSource.class), search.sync().errorThreshold(), search.diagnostics());
    }

    @Bean
    @ConditionalOnMissingBean(ConversationSearchWriter.class)
    ConversationSearchWriter searchWriter(ElasticsearchClient client, NewAiSearchProperties p) {
        return new ElasticsearchConversationSearchWriter(client, p);
    }

    @Bean
    @ConditionalOnProperty(prefix = "arte.ai-new-search", name = "initialize-on-startup", havingValue = "true")
    ConversationSearchBootstrap searchBootstrap(ElasticsearchClient client, ConversationSearchIndexLock lock, NewAiSearchProperties p,
                                                ConversationSearchSyncStore store, ConversationSearchWriter writer) {
        return new ConversationSearchBootstrap(client, lock, store, writer, p);
    }

    @Bean
    ConversationSearchSyncWorker searchSyncWorker(ConversationSearchSource source, ConversationSearchSyncStore store, ConversationSearchWriter writer, NewAiSearchProperties p) {
        return new ConversationSearchSyncWorker(source, store, writer, p);
    }

    @Bean
    ConversationSearchMaintenance searchMaintenance(ConversationSearchWriter writer, ConversationSearchSyncStore store, NewAiSearchProperties p, @Qualifier("newAiSearchScheduler") Scheduler scheduler, ConversationSearchSyncWorker worker, ConversationSearchIndexLock lock) {
        return new ConversationSearchMaintenance(writer, store, p, scheduler, worker, lock);
    }

    @Bean
    ConversationSearchScanLifecycle searchScanLifecycle(ConversationSearchSyncWorker worker, NewAiSearchProperties p, @Qualifier("newAiSearchScheduler") Scheduler scheduler,
                                                        ObjectProvider<ConversationSearchBootstrap> bootstrap) {
        return new ConversationSearchScanLifecycle(worker, p, scheduler, bootstrap.getIfAvailable());
    }
}
