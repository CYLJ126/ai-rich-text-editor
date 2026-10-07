package com.arte.ainew.config;

import com.arte.ainew.api.execution.ExecutionControl;
import com.arte.ainew.api.execution.ExecutionEventService;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.application.execution.*;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.spi.gateway.ModelGateway;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;

/**
 * 显式启用单次生成派发与读取；依赖已有受理及 ModelGateway，不执行 DDL 或账户初始化。
 * Worker 自动启动另由 worker-enabled 开关控制；默认可手动 pollOnce 做完整集成测试。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 16:14 ✾
 */
@AutoConfiguration(after = {NewAiAdmissionConfiguration.class, NewAiGenerationConfiguration.class})
@ConditionalOnProperty(prefix = "arte.ai-new-execution", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({NewAiExecutionProperties.class, NewAiEventProperties.class})
public class NewAiExecutionConfiguration {
    @Bean("newAiGenerationDispatcher")
    GenerationDispatcher dispatcher(AdmissionAuthorization authorization, FixedControlCatalog catalog,
                                    MybatisExecutionPersistence executions, MybatisPayloadPersistence payloads, ModelGateway gateway,
                                    NewAiProperties admission, NewAiExecutionProperties execution, @Qualifier("newAiClock") Clock clock) {
        return new GenerationDispatcher(authorization, catalog, executions, executions, executions, payloads, payloads,
                executions, gateway, admission, execution, clock);
    }

    @Bean("newAiInvocationCoordinator")
    InvocationCoordinator coordinator(GenerationDispatcher dispatcher, AdmissionAuthorization authorization,
                                      FixedControlCatalog catalog, MybatisExecutionPersistence executions,
                                      MybatisPayloadPersistence payloads, NewAiProperties properties, @Qualifier("newAiClock") Clock clock) {
        var admission = new AdmissionInvocationCoordinator(authorization, catalog, catalog, executions, payloads, executions, properties, clock);
        return new DefaultInvocationCoordinator(admission, dispatcher);
    }

    @Bean("newAiExecutionControl")
    ExecutionControl control(AdmissionAuthorization authorization, MybatisExecutionPersistence executions, InvocationCoordinator coordinator) {
        return new DefaultExecutionControl(authorization, executions, coordinator);
    }

    @Bean("newAiInvocationBudgetStatusResolver")
    InvocationBudgetStatusResolver budgetStatus(MybatisExecutionPersistence executions) {
        return new InvocationBudgetStatusResolver(executions, executions);
    }

    @Bean("newAiExecutionEventService")
    ExecutionEventService events(AdmissionAuthorization authorization, MybatisExecutionPersistence executions, MybatisPayloadPersistence payloads, LocalExecutionEventNotifier notifier, @Qualifier("newAiClock") Clock clock, InvocationBudgetStatusResolver budgetStatus) {
        return new DefaultExecutionEventService(authorization, executions, executions, payloads, notifier, clock, budgetStatus);
    }

    @Bean(name = "newAiWorkerScheduler", destroyMethod = "dispose")
    Scheduler scheduler() {
        return Schedulers.newSingle("arte-ainew-worker");
    }

    @Bean("newAiExecutionEventBroadcast")
    ExecutionEventBroadcast broadcast(NewAiEventProperties properties, LocalExecutionEventNotifier notifier,
                                      ObjectProvider<RedissonClient> redis, NewAiExecutionProperties execution) {
        if (properties.transport() == NewAiEventProperties.Transport.LOCAL) {
            return message -> Mono.fromRunnable(() -> notifier.publish(message));
        }
        ContractChecks.require(properties.publishTimeout().compareTo(execution.outboxLease()) < 0,
                "Event publish timeout must be shorter than the outbox lease");
        var client = redis.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("Redis event transport requires the existing RedissonClient bean");
        }
        return new RedisExecutionEventBroadcast(client, notifier, properties);
    }

    @Bean("newAiExecutionEventPublisher")
    ExecutionEventPublisher eventPublisher(MybatisExecutionPersistence executions, LocalExecutionEventNotifier notifier,
                                           NewAiExecutionProperties properties, @Qualifier("newAiWorkerScheduler") Scheduler scheduler, ExecutionEventBroadcast broadcast) {
        return new ExecutionEventPublisher(executions, notifier, properties, scheduler, broadcast);
    }

    @Bean("newAiInvocationDispatchWorker")
    InvocationDispatchWorker worker(MybatisExecutionPersistence executions, InvocationCoordinator coordinator,
                                    NewAiExecutionProperties properties, @Qualifier("newAiWorkerScheduler") Scheduler scheduler) {
        return new InvocationDispatchWorker(executions, executions, coordinator, properties, scheduler);
    }
}
