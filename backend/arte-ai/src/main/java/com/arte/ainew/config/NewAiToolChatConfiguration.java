package com.arte.ainew.config;

import com.arte.ainew.persistence.mybatis.MybatisToolChatRunStore;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import reactor.core.scheduler.Scheduler;

import javax.sql.DataSource;

/**
 * S4-02 内部持久化；装配零 SQL，不建表、不注册 HTTP、不启动 Run Worker。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 16:20 ✾
 */
@AutoConfiguration(after = NewAiAdmissionConfiguration.class)
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-tool.enabled"}, havingValue = "true")
public class NewAiToolChatConfiguration {

    @Bean
    MybatisToolChatRunStore toolChatRunStore(NewAiProperties properties, ListableBeanFactory beans, ExecutionRecordCodec codec,
                                             @Qualifier("newAiPersistenceScheduler") Scheduler scheduler) {
        return new MybatisToolChatRunStore(beans.getBean(properties.dataSourceBean(), DataSource.class), codec, scheduler);
    }
}
