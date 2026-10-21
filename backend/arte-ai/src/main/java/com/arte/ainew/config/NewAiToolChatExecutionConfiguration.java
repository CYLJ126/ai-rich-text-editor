package com.arte.ainew.config;

import com.arte.ainew.api.context.ContextService;
import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.application.tool.admission.ToolAdmissionService;
import com.arte.ainew.application.tool.chat.*;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.spi.auth.ToolChatAccess;
import com.arte.ainew.spi.gateway.ModelGateway;
import com.arte.ainew.spi.persistence.ToolChatRunStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;

/**
 * 显式开放真实工具聊天；缺失统一执行、预算或宿主授权则装配失败，不注册空循环。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 22:00 ✾
 */
@AutoConfiguration(after = {NewAiToolChatConfiguration.class, NewAiExecutionConfiguration.class})
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-tool.enabled", "ai-new-execution.enabled", "ai-new-tool-chat.enabled"}, havingValue = "true")
@EnableConfigurationProperties(NewAiToolChatProperties.class)
public class NewAiToolChatExecutionConfiguration {

    @Bean
    ToolChatEventPublisher toolChatEventPublisher(MybatisExecutionPersistence executions, ToolChatRunStore runs,
                                                  NewAiToolChatProperties properties, NewAiExecutionProperties execution,
                                                  @Qualifier("newAiWorkerScheduler") Scheduler scheduler) {
        return new ToolChatEventPublisher(executions, runs, properties, scheduler, execution.workerEnabled());
    }

    @Bean
    ToolChatService toolChatService(ToolChatRunStore runs, ConversationService conversations, MybatisExecutionPersistence executions,
                                    FixedControlCatalog catalog, ContextService contexts, ToolSetService sets, ToolChatBudgetSelector budgets,
                                    AdmissionAuthorization authorization, ToolChatAccess access, NewAiProperties properties,
                                    @Qualifier("newAiClock") Clock clock,
                                    ObjectProvider<ModelGateway> gateways) {
        return new ToolChatService(runs, conversations, executions, catalog, contexts, sets, budgets, authorization, access, properties, clock, gateways::getIfAvailable);
    }

    @Bean
    ToolChatCoordinator toolChatCoordinator(ToolChatRunStore runs, MybatisExecutionPersistence executions, MybatisPayloadPersistence results,
                                            InvocationCoordinator invocations, ToolAdmissionService tools, ToolChatBudgetSelector budgets,
                                            AdmissionAuthorization authorization, ToolChatAccess access, com.arte.ainew.spi.persistence.ToolAdmissionStore toolSnapshots, NewAiProperties properties,
                                            @Qualifier("newAiClock") Clock clock, NewAiToolChatProperties chat,
                                            com.arte.ainew.application.execution.GenerationDispatcher generation) {
        generation.toolChats(runs);
        return new ToolChatCoordinator(runs, executions, results, invocations, tools, budgets, authorization, access, toolSnapshots, properties, clock, chat.pollInterval());
    }

    @Bean
    ToolChatWorker toolChatWorker(ToolChatRunStore runs, ToolChatCoordinator coordinator, NewAiToolChatProperties properties,
                                  NewAiExecutionProperties execution, @Qualifier("newAiWorkerScheduler") Scheduler scheduler) {
        return new ToolChatWorker(runs, coordinator, properties, scheduler, execution.workerEnabled());
    }
}
