package com.arte.ai.service.tool;

import com.arte.ai.api.tool.Tool;
import com.arte.ai.api.tool.ToolProvider;
import com.arte.ai.common.enums.tool.ToolProviderTypeEnum;
import com.arte.ai.mapper.tool.ToolMapper;
import com.arte.ai.mapper.tool.ToolProviderMapper;
import com.arte.ai.mapper.tool.ToolVersionMapper;
import com.arte.ai.pojo.tool.ToolCatalogPage;
import com.arte.ai.pojo.tool.ToolDefinition;
import com.arte.ai.pojo.tool.ToolProviderView;
import com.arte.ai.pojo.tool.ToolReference;
import com.arte.ai.pojo.tool.po.ToolProviderPo;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ToolManagementQueryServiceTest {

    @Test
    public void shouldMergeRuntimeAndPersistedProviderMetadata() {
        ToolProviderPo persisted = new ToolProviderPo()
                .setProviderId("local-java")
                .setName("old name")
                .setProviderType("local")
                .setConfig(Map.of("old", true))
                .setCredentialReference("must-not-be-exposed")
                .setStatus("enabled");

        ToolProviderMapper providerMapper = proxy(ToolProviderMapper.class, (method, arguments) -> {
            if (method.equals("selectList")) {
                return List.of(persisted);
            }
            return defaultValue(method);
        });
        ToolMapper toolMapper = proxy(ToolMapper.class, (method, arguments) ->
                method.equals("countByProviderId") ? 3L : defaultValue(method));

        ToolManagementQueryService service = new ToolManagementQueryService(
                List.of(runtimeProvider()), providerMapper, toolMapper, emptyVersionMapper());

        List<ToolProviderView> providers = service.listProviders("ENABLED", "LOCAL");

        assertEquals(1, providers.size());
        ToolProviderView view = providers.getFirst();
        assertEquals("Runtime local tools", view.name());
        assertEquals("com.arte.tools", view.configuration().get("scanPackage"));
        assertTrue(view.configuration().containsKey("nullableDefault"));
        assertNull(view.configuration().get("nullableDefault"));
        assertTrue(view.loaded());
        assertEquals(3L, view.toolCount());
    }

    @Test
    public void shouldClampCatalogPaginationBeforeQueryingDatabase() {
        AtomicReference<Object[]> selectArguments = new AtomicReference<>();
        ToolMapper toolMapper = proxy(ToolMapper.class, (method, arguments) -> {
            if (method.equals("selectCatalog")) {
                selectArguments.set(arguments);
                return List.of();
            }
            if (method.equals("countCatalog")) {
                return 0L;
            }
            return defaultValue(method);
        });
        ToolManagementQueryService service = new ToolManagementQueryService(
                List.of(), emptyProviderMapper(), toolMapper, emptyVersionMapper());

        ToolCatalogPage page = service.catalog("summary", null, null, -2, 1000);

        assertEquals(1, page.current());
        assertEquals(200, page.size());
        assertNotNull(selectArguments.get());
        assertEquals(0L, selectArguments.get()[3]);
        assertEquals(200, selectArguments.get()[4]);
    }

    private ToolProvider runtimeProvider() {
        return new ToolProvider() {
            @Override
            public String getProviderId() {
                return "local-java";
            }

            @Override
            public String getName() {
                return "Runtime local tools";
            }

            @Override
            public ToolProviderTypeEnum getProviderType() {
                return ToolProviderTypeEnum.LOCAL;
            }

            @Override
            public Map<String, Object> getConfiguration() {
                Map<String, Object> configuration = new LinkedHashMap<>();
                configuration.put("scanPackage", "com.arte.tools");
                configuration.put("nullableDefault", null);
                return configuration;
            }

            @Override
            public List<ToolDefinition> listDefinitions() {
                return List.of();
            }

            @Override
            public Optional<Tool<?, ?>> resolve(ToolReference reference) {
                return Optional.empty();
            }

            @Override
            public CompletionStage<Void> refresh() {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private ToolProviderMapper emptyProviderMapper() {
        return proxy(ToolProviderMapper.class, (method, arguments) -> defaultValue(method));
    }

    private ToolVersionMapper emptyVersionMapper() {
        return proxy(ToolVersionMapper.class, (method, arguments) -> defaultValue(method));
    }

    private Object defaultValue(String method) {
        if (method.startsWith("select")) {
            return method.equals("selectList") || method.equals("selectEnabled")
                    || method.equals("selectVersions") ? List.of() : Optional.empty();
        }
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
