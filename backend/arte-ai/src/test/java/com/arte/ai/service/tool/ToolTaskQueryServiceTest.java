package com.arte.ai.service.tool;

import com.arte.ai.mapper.tool.ToolTaskMapper;
import com.arte.ai.pojo.tool.ToolTaskHandle;
import com.arte.ai.pojo.tool.ToolTaskPage;
import com.arte.ai.pojo.tool.po.ToolTaskPo;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ToolTaskQueryServiceTest {

    @Test
    public void shouldScopeAndClampTaskPageWithoutExposingResumeToken() {
        AtomicReference<Object[]> selectArguments = new AtomicReference<>();
        ToolTaskPo task = new ToolTaskPo()
                .setTaskId("task-1")
                .setCallId("call-1")
                .setStatus("running")
                .setProgress(BigDecimal.valueOf(0.25))
                .setProgressMessage("executing")
                .setResumeTokenHash("must-not-be-exposed")
                .setMetadata(Map.of(
                        "toolNamespace", "article",
                        "toolName", "summarize",
                        "toolVersion", "1.0.0"))
                .setRowVersion(3L);
        task.setCreateTime(LocalDateTime.of(2026, 9, 28, 10, 0));
        task.setUpdateTime(LocalDateTime.of(2026, 9, 28, 10, 1));

        ToolTaskMapper mapper = proxy(ToolTaskMapper.class, (method, arguments) -> {
            if (method.equals("selectOwned")) {
                selectArguments.set(arguments);
                return List.of(task);
            }
            if (method.equals("countOwned")) return 1L;
            return null;
        });

        ToolTaskPage page = new ToolTaskQueryService(mapper)
                .listOwned("owner-1", ToolTaskHandle.Status.RUNNING, -2, 999);

        assertEquals(1, page.current());
        assertEquals(200, page.size());
        assertEquals(1L, page.total());
        assertEquals("owner-1", selectArguments.get()[0]);
        assertEquals("running", selectArguments.get()[1]);
        assertEquals(0L, selectArguments.get()[2]);
        assertEquals(200, selectArguments.get()[3]);
        ToolTaskHandle handle = page.records().getFirst();
        assertEquals(ToolTaskHandle.Status.RUNNING, handle.status());
        assertEquals("article", handle.tool().namespace());
        assertNull(handle.resumeToken());
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
