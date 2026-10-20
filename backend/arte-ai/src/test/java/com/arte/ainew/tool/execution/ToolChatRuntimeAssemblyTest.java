package com.arte.ainew.tool.execution;

import com.arte.ainew.api.context.ContextService;
import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.context.TextContextService;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.application.tool.admission.ToolAdmissionService;
import com.arte.ainew.application.tool.chat.*;
import com.arte.ainew.config.NewAiToolChatExecutionConfiguration;
import com.arte.ainew.config.NewAiToolChatProperties;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.spi.auth.ToolChatAccess;
import com.arte.ainew.spi.persistence.ToolAdmissionStore;
import com.arte.ainew.spi.persistence.ToolChatRunStore;
import org.junit.Test;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class ToolChatRuntimeAssemblyTest {

    static Map<String, Object> flags() {
        return Map.of("arte.ai-new.enabled", true, "arte.ai-new-tool.enabled", true,
                "arte.ai-new-execution.enabled", true, "arte.ai-new-tool-chat.enabled", true);
    }

    @Test
    public void runtimeRequiresAllFourSwitchesAndDoesNotInventMissingDependencies() {
        for (String missing : flags().keySet())
            try (var c = new AnnotationConfigApplicationContext()) {
                var values = new HashMap<>(flags());
                values.remove(missing);
                c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", values));
                c.register(NewAiToolChatExecutionConfiguration.class);
                c.refresh();
                assertTrue(c.getBeansOfType(ToolChatService.class).isEmpty());
                assertTrue(c.getBeansOfType(ToolChatWorker.class).isEmpty());
            }
        try (var c = new AnnotationConfigApplicationContext()) {
            c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", flags()));
            c.register(NewAiToolChatExecutionConfiguration.class);
            assertThrows(BeansException.class, c::refresh);
        }
    }

    @Test
    public void enabledManualAssemblyDoesNotStartLoopsOrCreateRuns() throws Exception {
        try (var r = new ToolChatLoopTest.Rig(); var c = new AnnotationConfigApplicationContext()) {
            c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", flags()));
            c.getBeanFactory().registerSingleton("newAiProperties", r.properties);
            c.getBeanFactory().registerSingleton("newAiExecutionProperties", r.f.executionProperties);
            c.registerBean(ToolChatRunStore.class, () -> r.runs);
            c.registerBean(ConversationService.class, () -> r.conversations);
            c.registerBean(MybatisExecutionPersistence.class, () -> r.f.executions);
            c.registerBean(MybatisPayloadPersistence.class, () -> r.f.payloads);
            c.registerBean(FixedControlCatalog.class, () -> r.catalog);
            c.registerBean(ContextService.class, () -> new TextContextService(r.authorization, r.catalog, r.f.payloads, r.properties, r.f.clock, r.history));
            c.registerBean(ToolSetService.class, () -> new ToolSetService(r.f.bindingService, r.f.catalog, r.f.metadata, java.time.Duration.ofSeconds(5)));
            c.registerBean(ToolChatBudgetSelector.class, () -> r.budgets);
            c.registerBean(AdmissionAuthorization.class, () -> r.authorization);
            c.registerBean(ToolChatAccess.class, () -> r.access);
            c.registerBean(ToolAdmissionService.class, () -> r.toolAdmission);
            c.registerBean(ToolAdmissionStore.class, () -> r.f.store);
            c.registerBean(InvocationCoordinator.class, () -> r.invocationCoordinator);
            c.registerBean(com.arte.ainew.application.execution.GenerationDispatcher.class, () -> r.generation);
            c.registerBean("newAiClock", Clock.class, () -> r.f.clock);
            c.registerBean("newAiWorkerScheduler", Scheduler.class, () -> r.f.timer);
            c.register(NewAiToolChatExecutionConfiguration.class);
            c.refresh();
            assertNotNull(c.getBean(ToolChatService.class));
            assertFalse(c.getBean(ToolChatWorker.class).isRunning());
            assertFalse(c.getBean(ToolChatEventPublisher.class).isRunning());
            assertEquals(0, r.count("arte_ai_tool_chat_run"));
        }
    }

    @Test
    public void runEventOutboxPublishesTypedHintsAndNeverDispatchesModels() throws Exception {
        try (var r = new ToolChatLoopTest.Rig()) {
            r.submit();
            r.tick();
            var publisher = new ToolChatEventPublisher(r.f.executions, r.runs,
                    new NewAiToolChatProperties(true, false, null, null, 0), r.f.timer, false);
            var notices = new ArrayList<ToolChatEventPublisher.Notice>();
            var subscriber = publisher.notices().subscribe(notices::add);
            try {
                assertEquals(Integer.valueOf(2), publisher.pollOnce().block(ToolChatLoopTest.WAIT));
                assertEquals(Integer.valueOf(0), publisher.pollOnce().block(ToolChatLoopTest.WAIT));
                assertEquals(List.of(1L, 2L), notices.stream().map(ToolChatEventPublisher.Notice::sequence).sorted().toList());
                assertTrue(notices.stream().allMatch(n -> n.runId().equals("run") && n.owner().equals(r.f.owner)));
                assertEquals(0, r.modelCalls.get());
                assertEquals(0, r.count("arte_ai_invocation"));
            } finally {
                subscriber.dispose();
                publisher.stop();
            }
        }
    }
}
