package com.arte.ainew.config;

import com.arte.ainew.api.execution.ExecutionControl;
import com.arte.ainew.api.execution.ExecutionEventService;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.application.execution.*;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.spi.gateway.ModelGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
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
@EnableConfigurationProperties(NewAiExecutionProperties.class)
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

    @Bean("newAiExecutionEventService")
    ExecutionEventService events(AdmissionAuthorization authorization, MybatisExecutionPersistence executions, MybatisPayloadPersistence payloads) {
        return new DefaultExecutionEventService(authorization, executions, executions, payloads);
    }

    @Bean(name = "newAiWorkerScheduler", destroyMethod = "dispose")
    Scheduler scheduler() {
        return Schedulers.newSingle("arte-ainew-worker");
    }

    @Bean("newAiInvocationDispatchWorker")
    InvocationDispatchWorker worker(MybatisExecutionPersistence executions, InvocationCoordinator coordinator,
                                    NewAiExecutionProperties properties, @Qualifier("newAiWorkerScheduler") Scheduler scheduler) {
        return new InvocationDispatchWorker(executions, executions, coordinator, properties, scheduler);
    }
}
