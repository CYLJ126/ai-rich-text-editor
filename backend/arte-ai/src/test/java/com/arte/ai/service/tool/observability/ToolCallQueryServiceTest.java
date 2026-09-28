package com.arte.ai.service.tool.observability;

import com.arte.ai.mapper.tool.ToolCallMapper;
import com.arte.ai.mapper.tool.ToolCallResultMapper;
import com.arte.ai.mapper.tool.ToolExecutionEventMapper;
import com.arte.ai.pojo.tool.ToolCallPage;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ToolCallQueryServiceTest {

    @Test
    public void shouldNormalizeScopeAndClampPage() {
        AtomicReference<Object[]> selectArguments = new AtomicReference<>();
        ToolCallMapper callMapper = proxy(ToolCallMapper.class, (method, arguments) -> {
            if (method.equals("selectDetailsPage")) {
                selectArguments.set(arguments);
                return List.of();
            }
            if (method.equals("countDetails")) return 0L;
            return Optional.empty();
        });
        ToolCallQueryService service = service(callMapper);

        ToolCallPage page = service.page("owner-1", " article:summarize ", "SUCCEEDED", -1, 999);

        assertEquals(1, page.current());
        assertEquals(200, page.size());
        assertEquals("owner-1", selectArguments.get()[0]);
        assertEquals("article:summarize", selectArguments.get()[1]);
        assertEquals("succeeded", selectArguments.get()[2]);
        assertEquals(0L, selectArguments.get()[3]);
        assertEquals(200, selectArguments.get()[4]);
    }

    @Test
    public void shouldRejectUnknownStatus() {
        ToolCallQueryService service = service(proxy(ToolCallMapper.class,
                (method, arguments) -> List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> service.page("owner-1", null, "unknown", 1, 20));
    }

    private ToolCallQueryService service(ToolCallMapper callMapper) {
        ToolCallResultMapper resultMapper = proxy(ToolCallResultMapper.class,
                (method, arguments) -> method.equals("selectByCallIds") ? List.of() : Optional.empty());
        ToolExecutionEventMapper eventMapper = proxy(ToolExecutionEventMapper.class,
                (method, arguments) -> List.of());
        return new ToolCallQueryService(callMapper, resultMapper, eventMapper, null);
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
