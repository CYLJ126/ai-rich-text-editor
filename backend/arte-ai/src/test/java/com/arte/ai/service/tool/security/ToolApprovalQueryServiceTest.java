package com.arte.ai.service.tool.security;

import com.arte.ai.mapper.tool.ToolApprovalMapper;
import com.arte.ai.pojo.tool.ToolApprovalPage;
import com.arte.ai.pojo.tool.po.ToolApprovalPo;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ToolApprovalQueryServiceTest {

    @Test
    public void shouldScopeClampAndExposeOnlySafeApprovalView() {
        AtomicReference<Object[]> selectArguments = new AtomicReference<>();
        ToolApprovalPo approval = approval("owner-1");
        ToolApprovalMapper mapper = proxy(ToolApprovalMapper.class, (method, arguments) -> {
            if (method.equals("selectOwned")) {
                selectArguments.set(arguments);
                return List.of(approval);
            }
            if (method.equals("countOwned")) return 1L;
            return Optional.empty();
        });

        ToolApprovalPage page = new ToolApprovalQueryService(mapper)
                .listOwned("owner-1", "EXPIRED", -1, 999);

        assertEquals(1, page.current());
        assertEquals(200, page.size());
        assertEquals("owner-1", selectArguments.get()[0]);
        assertEquals("expired", selectArguments.get()[1]);
        assertEquals(0L, selectArguments.get()[2]);
        assertEquals(200, selectArguments.get()[3]);
        assertEquals("expired", page.records().getFirst().status());
        assertTrue(page.records().getFirst().displayArguments().containsKey("optional"));
        assertNull(page.records().getFirst().displayArguments().get("optional"));
    }

    @Test
    public void shouldRejectUnsupportedStatusAndForeignDetail() {
        ToolApprovalPo approval = approval("another-owner");
        ToolApprovalMapper mapper = proxy(ToolApprovalMapper.class, (method, arguments) -> {
            if (method.equals("selectByRequestId")) return Optional.of(approval);
            if (method.equals("selectOwned")) return List.of();
            if (method.equals("countOwned")) return 0L;
            return null;
        });
        ToolApprovalQueryService service = new ToolApprovalQueryService(mapper);

        assertTrue(service.findOwned("owner-1", "approval-1").isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> service.listOwned("owner-1", "unknown", 1, 20));
    }

    private ToolApprovalPo approval(String owner) {
        LinkedHashMap<String, Object> displayArguments = new LinkedHashMap<>();
        displayArguments.put("token", "***");
        displayArguments.put("optional", null);
        ToolApprovalPo approval = new ToolApprovalPo()
                .setRequestId("approval-1")
                .setCallId("call-1")
                .setTaskId("task-1")
                .setToolId("article:summarize")
                .setToolVersion("1.0.0")
                .setArgumentsDigest("abcd")
                .setDisplayArguments(displayArguments)
                .setSummary("Approve tool")
                .setStatus("pending")
                .setExpiresAt(LocalDateTime.now().minusMinutes(1));
        approval.setId(1L);
        approval.setCreateBy(owner);
        approval.setCreateTime(LocalDateTime.now().minusMinutes(2));
        approval.setUpdateTime(LocalDateTime.now().minusMinutes(1));
        return approval;
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
