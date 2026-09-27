package com.arte.ai.service.tool.security;

import com.arte.ai.api.tool.ToolTaskManager;
import com.arte.ai.common.enums.tool.NeverToolCancellation;
import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;
import com.arte.ai.config.ToolExecutionProperties;
import com.arte.ai.mapper.tool.ToolApprovalMapper;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.pojo.tool.po.ToolApprovalPo;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class DatabaseToolApprovalServiceTest {

    @Test
    public void shouldPersistRedactedArgumentsDigestAndExpiryAndDetectTampering() {
        AtomicReference<ToolApprovalPo> stored = new AtomicReference<>();
        ToolApprovalMapper mapper = (ToolApprovalMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ToolApprovalMapper.class},
                (proxy, method, arguments) -> {
                    if ("insert".equals(method.getName())) {
                        stored.set((ToolApprovalPo) arguments[0]);
                        return 1;
                    }
                    if ("selectByRequestId".equals(method.getName())) {
                        return Optional.ofNullable(stored.get());
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(this, arguments);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutionProperties properties = new ToolExecutionProperties();
        properties.setApprovalTimeout(Duration.ofMinutes(30));
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolTaskManager> taskManagers = (ObjectProvider<ToolTaskManager>)
                Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{ObjectProvider.class}, (proxy, method, arguments) -> {
                            if ("getIfAvailable".equals(method.getName())) return null;
                            if ("iterator".equals(method.getName())) return java.util.Collections.emptyIterator();
                            if ("stream".equals(method.getName())
                                    || "orderedStream".equals(method.getName())) return java.util.stream.Stream.empty();
                            throw new UnsupportedOperationException(method.getName());
                        });
        DatabaseToolApprovalService service = new DatabaseToolApprovalService(mapper, objectMapper,
                new ToolArgumentDigest(objectMapper), new ToolDataSanitizer(), properties,
                taskManagers);
        Instant before = Instant.now();

        ToolApprovalRequest approval = service.requestApproval(invocation(arguments("article", "secret-value")))
                .toCompletableFuture().join();

        ToolApprovalPo persisted = stored.get();
        assertNotNull(persisted);
        assertEquals(approval.argumentsDigest(), persisted.getArgumentsDigest());
        assertTrue(approval.expiresAt().isAfter(before.plus(Duration.ofMinutes(29))));
        Map<?, ?> displayed = (Map<?, ?>) persisted.getDisplayArguments().get("arguments");
        assertEquals("***", displayed.get("apiKey"));
        assertTrue(service.matchesArguments(approval.requestId(),
                new DynamicToolRequest(arguments("article", "secret-value"))));
        assertFalse(service.matchesArguments(approval.requestId(),
                new DynamicToolRequest(arguments("changed", "secret-value"))));
    }

    private ToolInvocation<DynamicToolRequest> invocation(Map<String, Object> arguments) {
        ToolExecutionContext context = new ToolExecutionContext("run", null, null, "trace", "span",
                new ToolPrincipal("owner", "owner", Set.of(), Set.of()),
                Instant.now().plusSeconds(60), NeverToolCancellation.INSTANCE,
                "idempotency", null, Map.of());
        ToolExecutionPolicy policy = new ToolExecutionPolicy(ToolExecutionModeEnum.BLOCKING,
                Duration.ofSeconds(30), 0, Duration.ZERO, 1024, true, false);
        return new ToolInvocation<>("call", new ToolReference("article", "publish", "1.0.0"),
                new DynamicToolRequest(arguments), context, policy);
    }

    private Map<String, Object> arguments(String title, String apiKey) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("title", title);
        arguments.put("apiKey", apiKey);
        return arguments;
    }
}
