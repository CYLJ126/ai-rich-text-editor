package com.arte.ainew.tool.chat;

import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.config.NewAiToolChatConfiguration;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolChatRunStore;
import org.junit.Test;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ToolChatRunAssemblyTest {

    @Test
    public void bothSwitchesAreRequiredAndDisabledConfigurationNeedsNoStorageDependencies() {
        for (var flags : List.of(Map.<String, Object>of(), Map.<String, Object>of("arte.ai-new.enabled", true), Map.<String, Object>of("arte.ai-new-tool.enabled", true))) {
            try (var c = new AnnotationConfigApplicationContext()) {
                c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", flags));
                c.register(NewAiToolChatConfiguration.class);
                c.refresh();
                assertTrue(c.getBeansOfType(ToolChatRunStore.class).isEmpty());
            }
        }
    }

    @Test
    public void enabledAssemblyDoesNotConnectWriteDdlOrStartWorkersAndRequiresTheRealDatasource() {
        var connections = new AtomicInteger();
        try (var c = configured()) {
            c.registerBean("runDataSource", DataSource.class, () -> (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    connections.incrementAndGet();
                    throw new AssertionError("No startup SQL");
                }
                return null;
            }));
            c.refresh();
            assertNotNull(c.getBean(ToolChatRunStore.class));
            assertEquals(0, connections.get());
        }
        try (var missing = configured()) {
            assertThrows(BeansException.class, missing::refresh);
        }
    }

    private AnnotationConfigApplicationContext configured() {
        var c = new AnnotationConfigApplicationContext();
        c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of("arte.ai-new.enabled", true, "arte.ai-new-tool.enabled", true)));
        c.registerBean(NewAiProperties.class, () -> new NewAiProperties(true, "runDataSource", "release", null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "DATABASE"));
        c.registerBean(ExecutionRecordCodec.class, JacksonExecutionRecordCodec::new);
        c.registerBean("newAiPersistenceScheduler", Scheduler.class, () -> Schedulers.newBoundedElastic(1, 8, "run-assembly-test"), definition -> definition.setDestroyMethodName("dispose"));
        c.register(NewAiToolChatConfiguration.class);
        return c;
    }
}
