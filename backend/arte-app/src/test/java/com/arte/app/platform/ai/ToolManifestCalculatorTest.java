package com.arte.app.platform.ai;

import com.arte.ainew.application.tool.discovery.StaticToolDiscovery;
import com.arte.ainew.application.tool.discovery.StaticToolRegistry;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.config.NewAiGenerationProperties;
import com.arte.ainew.config.NewAiSchedulingProperties;
import com.arte.ainew.infrastructure.http.*;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.app.platform.ai.tool.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具清单 digest 计算
 * <p>
 * 生成的值，更新到配置文件的 arte.ai-new-execution.scheduling.tool-manifest 参数，以及 arte_ai_dispatch_pool 表
 * <p>
 * 手工计算当前部署的完整工具清单，不启动应用、Worker、数据库或远端交互。
 * 使用正式注册表及发现校验算法；业务执行依赖仅为构造静态定义而留空，禁止调用工具。
 * 类名不匹配 Surefire 默认测试模式；IDE 可运行本测试，命令行显式指定 -Dtest=ToolManifestCalculator。
 * 新增工具装配时同步维护 metadataRegistry，并核对输出的完整工具列表。
 */
class ToolManifestCalculatorTest {

    private static final String RESOURCE = "ainew/config/application.properties";

    @Test
    void calculateCurrentDeploymentManifest() throws Exception {
        var properties = new Properties();
        try (var input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(input, "缺少过滤后的 AI 配置，请先执行 Maven process-resources");
            properties.load(input);
        }
        var environment = new StandardEnvironment();
        // 系统属性及环境变量优先；不加载 DataSource 或整个业务配置对象。
        environment.getPropertySources().addLast(new PropertiesPropertySource("filtered-ai", properties));
        assertTrue(environment.getRequiredProperty("arte.ai-new.enabled", Boolean.class), "当前配置未启用 New-AI");
        assertTrue(environment.getRequiredProperty("arte.ai-new-tool.enabled", Boolean.class), "当前配置未启用 TOOL");

        var runtimes = new ArrayList<HttpConnectionRuntime>();
        var scheduler = Schedulers.newSingle("tool-manifest-calculator");
        try {
            var registry = metadataRegistry(environment, runtimes);
            var batch = new AtomicReference<ToolCatalogStore.Batch>();
            var discovery = new StaticToolDiscovery(registry, forbiddenStore(), new ToolSchemaValidator(), scheduler,
                    Clock.systemUTC(), Duration.ofSeconds(10));
            // 用内存回执代替数据库提交，其余加载、去重、契约及 Schema 校验复用正式发现链路。
            var refresh = discovery.refresh(candidate -> {
                batch.set(candidate);
                return Mono.just(StoreOutcome.applied(candidate));
            }).block(Duration.ofSeconds(30));
            assertNotNull(refresh);
            assertTrue(refresh.state().discoveryHealthy(), () -> "静态定义校验失败：" + refresh.code());
            var manifest = refresh.state().snapshot().checksum();
            assertEquals(StaticToolDiscovery.manifest(batch.get()), manifest);

            // 策略摘要必须按本次新 manifest 计算，不能沿用配置中的旧清单值。
            environment.getPropertySources().addFirst(new MapPropertySource("calculated-manifest", Map.of(
                    "arte.ai-new-execution.scheduling.tool-manifest", manifest)));
            var policy = Binder.get(environment).bind("arte.ai-new-execution.scheduling",
                    Bindable.of(NewAiSchedulingProperties.class)).get();
            var report = new StringBuilder("# 当前静态来源；不表示已发布、已建绑或调度池就绪。\n");
            batch.get().tools().stream().sorted(java.util.Comparator.comparing(t -> t.tool().definition().id()))
                    .forEach(tool -> report.append("# tool=").append(tool.tool().definition().id())
                            .append('@').append(tool.tool().definition().version())
                            .append(" timeout=").append(tool.tool().defaultPolicy().timeout()).append('\n'));
            report.append("arte.ai-new-execution.scheduling.tool-manifest=").append(manifest).append('\n')
                    .append("# policy_digest=").append(CanonicalJson.key(policy.toString())).append('\n');
            var target = Path.of("target", "tool-manifest.properties");
            Files.createDirectories(target.getParent());
            Files.writeString(target, report);
            System.out.print(report);
            System.out.println("MANIFEST_REPORT=" + target.toAbsolutePath());
        } finally {
            scheduler.dispose();
            runtimes.forEach(HttpConnectionRuntime::close);
        }
    }

    private StaticToolRegistry metadataRegistry(StandardEnvironment environment, List<HttpConnectionRuntime> runtimes) {
        var binder = Binder.get(environment);
        var clock = Clock.systemUTC();
        var text = new LocalTextTools(clock);
        var writeProperties = binder.bind("arte.ai-new-tool-write", Bindable.of(LocalToolWriteProperties.class))
                .orElse(new LocalToolWriteProperties(false));
        var writes = new LocalWorkspaceTools(writeProperties, null, null, null, null, null, null, null, null, clock);
        var beans = new DefaultListableBeanFactory();
        if (environment.getProperty("arte.ai-new-tool-http.enabled", Boolean.class, false)) {
            var properties = binder.bind("arte.ai-new-tool-http", Bindable.of(HttpHealthToolProperties.class)).get();
            var runtime = metadataRuntime(properties.allowedOrigins());
            runtimes.add(runtime);
            beans.registerSingleton("httpHealth", new PlatformHttpToolConfiguration().httpHealthTool(properties, runtime));
        }
        if (environment.getProperty("arte.ai-new-tool-mcp.enabled", Boolean.class, false)) {
            var properties = binder.bind("arte.ai-new-tool-mcp", Bindable.of(McpHealthToolProperties.class)).get();
            var runtime = metadataRuntime(properties.allowedOrigins());
            runtimes.add(runtime);
            var configuration = new PlatformMcpToolConfiguration();
            beans.registerSingleton("mcpHealth", configuration.mcpHealthTool(properties, runtime));
            beans.registerSingleton("bing", configuration.modelScopeBingToolAdapter(runtime));
            new HttpEgressPolicy(new NewAiGenerationProperties(false, properties.allowedOrigins(), List.of(),
                    0, 0, 0, null, 0, 0)).validateOrigin(ModelScopeBingToolAdapter.connectionDefinition().endpoint());
        }
        return new PlatformToolExecutionConfiguration().staticToolRegistry(text, writes, writeProperties,
                beans.getBeanProvider(HttpHealthToolAdapter.class), beans.getBeanProvider(McpHealthToolAdapter.class),
                beans.getBeanProvider(ModelScopeBingToolAdapter.class));
    }

    /**
     * 只允许构造元数据；意外调用工具时，在连接解析边界立即失败，不解析 DNS 或发送 HTTP。
     */
    private HttpConnectionRuntime metadataRuntime(List<URI> origins) {
        return new HttpConnectionRuntime(
                (reference, context) -> Mono.error(new AssertionError("Manifest calculation cannot acquire connections")),
                null,
                (reference, context) -> Mono.error(new AssertionError("Manifest calculation cannot resolve credentials")),
                new NewAiGenerationProperties(false, origins, List.of(), 0, 0, 0, null, 0, 0), Clock.systemUTC());
    }

    private ToolCatalogStore forbiddenStore() {
        return (ToolCatalogStore) Proxy.newProxyInstance(ToolCatalogStore.class.getClassLoader(),
                new Class<?>[]{ToolCatalogStore.class}, (proxy, method, args) -> {
                    throw new AssertionError("Manifest calculation cannot access persistent storage");
                });
    }
}
