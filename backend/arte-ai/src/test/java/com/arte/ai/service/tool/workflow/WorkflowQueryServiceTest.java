package com.arte.ai.service.tool.workflow;

import com.arte.ai.mapper.tool.*;
import com.arte.ai.pojo.tool.WorkflowRunPage;
import com.arte.ai.pojo.tool.WorkflowSummaryPage;
import com.arte.ai.service.tool.security.ToolDataSanitizer;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class WorkflowQueryServiceTest {

    @Test
    public void shouldScopeWorkflowCatalogToOwnerAndClampPagination() {
        AtomicReference<Object[]> arguments = new AtomicReference<>();
        WorkflowMapper workflows = proxy(WorkflowMapper.class, (method, values) -> {
            if (method.equals("selectOwnedPage")) {
                arguments.set(values);
                return List.of();
            }
            if (method.equals("countOwned")) return 0L;
            return defaultValue(method);
        });

        WorkflowSummaryPage page = service(workflows, emptyRuns())
                .workflows("owner-1", " article ", "PUBLISHED", -1, 999);

        assertEquals(1, page.current());
        assertEquals(200, page.size());
        assertEquals("owner-1", arguments.get()[0]);
        assertEquals("article", arguments.get()[1]);
        assertEquals("published", arguments.get()[2]);
        assertEquals(0L, arguments.get()[3]);
        assertEquals(200, arguments.get()[4]);
    }

    @Test
    public void shouldNormalizeRunStatusAndRejectUnknownValues() {
        AtomicReference<Object[]> arguments = new AtomicReference<>();
        WorkflowRunMapper runs = proxy(WorkflowRunMapper.class, (method, values) -> {
            if (method.equals("selectOwnedPage")) {
                arguments.set(values);
                return List.of();
            }
            if (method.equals("countOwned")) return 0L;
            return defaultValue(method);
        });
        WorkflowQueryService service = service(emptyWorkflows(), runs);

        WorkflowRunPage page = service.runs("owner-1", " wf-1 ", "WAITING-APPROVAL", 2, 20);

        assertEquals(2, page.current());
        assertEquals("owner-1", arguments.get()[0]);
        assertEquals("wf-1", arguments.get()[1]);
        assertEquals("waiting_approval", arguments.get()[2]);
        assertEquals(20L, arguments.get()[3]);
        assertThrows(IllegalArgumentException.class,
                () -> service.runs("owner-1", null, "unknown", 1, 20));
    }

    private WorkflowQueryService service(WorkflowMapper workflows, WorkflowRunMapper runs) {
        return new WorkflowQueryService(workflows,
                proxy(WorkflowVersionMapper.class, (method, values) -> defaultValue(method)),
                runs,
                proxy(WorkflowNodeRunMapper.class, (method, values) -> defaultValue(method)),
                proxy(WorkflowCheckpointMapper.class, (method, values) -> defaultValue(method)),
                new ToolDataSanitizer());
    }

    private WorkflowMapper emptyWorkflows() {
        return proxy(WorkflowMapper.class, (method, values) -> defaultValue(method));
    }

    private WorkflowRunMapper emptyRuns() {
        return proxy(WorkflowRunMapper.class, (method, values) -> defaultValue(method));
    }

    private Object defaultValue(String method) {
        if (method.startsWith("select")) {
            return method.equals("selectOwnedPage") || method.equals("selectVersions")
                    || method.equals("selectByRunId") ? List.of() : Optional.empty();
        }
        if (method.startsWith("count")) return 0L;
        return null;
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> invocation.invoke(method.getName(), arguments));
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] arguments);
    }
}
