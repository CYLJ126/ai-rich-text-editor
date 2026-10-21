package com.arte.ainew.config;

import com.arte.ainew.api.execution.ExecutionControl;
import com.arte.ainew.api.execution.ExecutionEventService;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.application.execution.*;
import com.arte.ainew.application.tool.discovery.StaticToolDiscovery;
import com.arte.ainew.application.tool.execution.ToolDispatcher;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.spi.gateway.ModelGateway;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;

/**
 * 显式启用派发路由与读取；生成依赖 ModelGateway，TOOL 依赖宿主授权和 ToolGateway，至少具备一种派发器。
 * 不执行 DDL 或账户初始化。
 * Worker 自动启动另由 worker-enabled 开关控制；默认可手动 pollOnce 做完整集成测试。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 16:14 ✾
 */
@AutoConfiguration(after = {NewAiAdmissionConfiguration.class, NewAiGenerationConfiguration.class, NewAiToolExecutionConfiguration.class})
@ConditionalOnProperty(prefix = "arte.ai-new-execution", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({NewAiExecutionProperties.class, NewAiEventProperties.class})
public class NewAiExecutionConfiguration {

    @Bean("newAiLiveTextNotifier")
    LiveTextNotifier liveTextNotifier() {
        return new LiveTextNotifier();
    }

    @Bean("newAiGenerationDispatcher")
    @ConditionalOnBean(ModelGateway.class)
    GenerationDispatcher dispatcher(AdmissionAuthorization authorization, FixedControlCatalog catalog,
                                    MybatisExecutionPersistence executions, MybatisPayloadPersistence payloads, ModelGateway gateway,
                                    NewAiProperties admission, NewAiExecutionProperties execution, @Qualifier("newAiClock") Clock clock,
                                    LiveTextNotifier liveText, ObjectProvider<com.arte.ainew.spi.persistence.ToolChatRunStore> runs) {
        var dispatcher = new GenerationDispatcher(authorization, catalog, executions, executions, executions, payloads, payloads,
                executions, gateway, admission, execution, clock, liveText);
        var store = runs.getIfAvailable();
        return store == null ? dispatcher : dispatcher.toolChats(store);
    }

    @Bean("newAiInvocationCoordinator")
    InvocationCoordinator coordinator(ObjectProvider<GenerationDispatcher> dispatcher, ObjectProvider<ToolDispatcher> tools, AdmissionAuthorization authorization,
                                      FixedControlCatalog catalog, MybatisExecutionPersistence executions,
                                      MybatisPayloadPersistence payloads, NewAiProperties properties, @Qualifier("newAiClock") Clock clock) {
        var admission = new AdmissionInvocationCoordinator(authorization, catalog, catalog, executions, payloads, executions, properties, clock);
        return new DefaultInvocationCoordinator(admission, dispatcher.getIfAvailable(), executions, tools.getIfAvailable());
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
    ExecutionEventService events(AdmissionAuthorization authorization, MybatisExecutionPersistence executions, MybatisPayloadPersistence payloads, LocalExecutionEventNotifier notifier, @Qualifier("newAiClock") Clock clock, InvocationBudgetStatusResolver budgetStatus, LiveTextNotifier liveText, org.springframework.beans.factory.ObjectProvider<com.arte.ainew.spi.conversation.ConversationContentVisibility> visibility) {
        return new DefaultExecutionEventService(authorization, executions, executions, payloads, notifier, clock, budgetStatus, liveText, visibility.getIfAvailable());
    }

    @Bean(name = "newAiWorkerScheduler", destroyMethod = "dispose")
    Scheduler scheduler() {
        return Schedulers.newSingle("arte-ainew-worker");
    }

    @Bean("newAiExecutionEventBroadcast")
    ExecutionEventBroadcast broadcast(NewAiEventProperties properties, LocalExecutionEventNotifier notifier,
                                      ObjectProvider<RedissonClient> redis, NewAiExecutionProperties execution, LiveTextNotifier liveText,
                                      LocalDispatchNotifier dispatchNotifier) {
        if (properties.transport() == NewAiEventProperties.Transport.LOCAL) {
            return message -> Mono.fromRunnable(() -> notifier.publish(message));
        }
        ContractChecks.require(properties.publishTimeout().compareTo(execution.outboxLease()) < 0,
                "Event publish timeout must be shorter than the outbox lease");
        var client = redis.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("Redis event transport requires the existing RedissonClient bean");
        }
        return new RedisExecutionEventBroadcast(client, notifier, properties, liveText, dispatchNotifier);
    }

    @Bean("newAiLiveTextPublisher")
    LiveTextPublisher liveTextPublisher(LiveTextNotifier liveText, ExecutionEventBroadcast broadcast) {
        return new LiveTextPublisher(liveText, broadcast);
    }

    @Bean("newAiExecutionEventPublisher")
    ExecutionEventPublisher eventPublisher(MybatisExecutionPersistence executions, LocalExecutionEventNotifier notifier,
                                           NewAiExecutionProperties properties, @Qualifier("newAiWorkerScheduler") Scheduler scheduler, ExecutionEventBroadcast broadcast) {
        return new ExecutionEventPublisher(executions, notifier, properties, scheduler, broadcast);
    }

    @Bean("newAiInvocationDispatchWorker")
    InvocationDispatchWorker worker(MybatisExecutionPersistence executions, InvocationCoordinator coordinator,
                                    NewAiExecutionProperties properties, @Qualifier("newAiWorkerScheduler") Scheduler scheduler,
                                    LocalDispatchNotifier notifier,
                                    ObjectProvider<StaticToolDiscovery> discovery) {
        return new InvocationDispatchWorker(executions, executions, coordinator, properties, scheduler, notifier, properties.scheduling(), () -> {
            var source = discovery.getIfAvailable();
            if (source == null) return null;
            var state = source.state();
            return state.discoveryHealthy() && state.snapshot() != null ? state.snapshot().checksum() : null;
        });
    }

    /**
     * 仅受理节点也广播提交提示；Worker 在远端仍通过数据库领取。
     */
    @Bean("newAiDispatchWakeupPublisher")
    DispatchWakeupPublisher dispatchWakeupPublisher(LocalDispatchNotifier notifier, ExecutionEventBroadcast broadcast,
                                                    @Qualifier("newAiWorkerScheduler") Scheduler scheduler) {
        return new DispatchWakeupPublisher(notifier, broadcast, scheduler);
    }
}
