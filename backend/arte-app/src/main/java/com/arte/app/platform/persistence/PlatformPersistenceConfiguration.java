package com.arte.app.platform.persistence;

import com.arte.core.spi.platform.IdentityProvider;
import com.arte.core.spi.platform.PlatformIdGenerator;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * Storage infrastructure only. Default services and HTTP are assembled in subsequent batches.
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:03 ✾
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte.platform.persistence", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PlatformPersistenceProperties.class)
@Import(PlatformMybatisConfiguration.class)
public class PlatformPersistenceConfiguration {
    @Bean
    PlatformJson platformJson() {
        return new PlatformJson();
    }

    @Bean
    JdbcPublicTransactionRunner platformTransactionRunner(
            @Qualifier("appDataSource") DataSource s,
            IdentityProvider identity,
            PlatformPersistenceProperties p) {
        // Deliberately no second default transaction-manager bean; physical app transactions join
        // by
        // datasource.
        return new JdbcPublicTransactionRunner(
                s, new DataSourceTransactionManager(s), p.transactionTimeoutSeconds());
    }

    @Bean(destroyMethod = "dispose")
    Scheduler platformPersistenceScheduler(PlatformPersistenceProperties p) {
        return Schedulers.newBoundedElastic(
                p.workers(), p.queueCapacity(), "platform-persistence", 60, true);
    }

    @Bean
    PlatformPersistenceExecutor platformPersistenceExecutor(
            @Qualifier("platformPersistenceScheduler") Scheduler s, JdbcPublicTransactionRunner t) {
        return new PlatformPersistenceExecutor(s, t);
    }

    @Bean
    PlatformIdGenerator platformIdGenerator() {
        return () -> UUID.randomUUID().toString();
    }

    @Bean
    WorkspaceCursorStore workspaceCursorStore(
            RedissonClient r, PlatformJson j, PlatformPersistenceProperties p) {
        return new RedisWorkspaceCursorStore(r, j, p.cursorTtl(), p.redisPrefix() + "cursor:v2:");
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(
            prefix = "arte.platform.persistence",
            name = "outbox-enabled",
            havingValue = "true")
    RedisOutboxPublisher platformOutboxPublisher(
            RedissonClient r,
            com.arte.app.api.platform.OutboxDeliveryService o,
            PlatformPersistenceExecutor e,
            PlatformJson j,
            PlatformPersistenceProperties p) {
        return new RedisOutboxPublisher(r, o, e, j, p);
    }
}
