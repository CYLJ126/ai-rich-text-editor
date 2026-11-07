package com.arte.ainew.admission;

import com.arte.ainew.config.HttpRuntimeOptions;
import com.arte.ainew.config.NewAiHttpProperties;
import org.junit.Test;
import org.springframework.beans.BeansException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * 验证三类 HTTP 参数的独立绑定、缺省值及资源边界，无数据库或网络依赖。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 14:25 ✾
 */
public class HttpRuntimeConfigurationTest {

    /**
     * 只注册 HTTP 参数入口，证明绑定不依赖模型生成装配。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NewAiHttpProperties.class)
    static class Settings {
    }

    private NewAiHttpProperties bind(Map<String, Object> values) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("http-test", values));
            context.register(Settings.class);
            context.refresh();
            return context.getBean(NewAiHttpProperties.class);
        }
    }

    @Test
    public void emptyConfigurationKeepsExistingModelAndToolDefaults() {
        var http = bind(Map.of());
        assertEquals(new HttpRuntimeOptions(32, 8, 32, Duration.ofSeconds(5), 65_536), http.model());
        assertEquals(new HttpRuntimeOptions(8, 4, 8, Duration.ofSeconds(2), 65_536), http.toolHttp());
        assertEquals(http.toolHttp(), http.toolMcp());
    }

    @Test
    public void partialOverridesKeepTheirOwnGroupDefaultsAndDoNotAffectOtherRuntimes() {
        var http = bind(Map.of("arte.ai-new-http.model.max-pools", "12",
                "arte.ai-new-http.tool-http.connections-per-pool", "2",
                "arte.ai-new-http.tool-mcp.max-in-memory-bytes", "131072"));
        assertEquals(new HttpRuntimeOptions(12, 8, 32, Duration.ofSeconds(5), 65_536), http.model());
        assertEquals(new HttpRuntimeOptions(8, 2, 8, Duration.ofSeconds(2), 65_536), http.toolHttp());
        assertEquals(new HttpRuntimeOptions(8, 4, 8, Duration.ofSeconds(2), 131_072), http.toolMcp());
    }

    @Test
    public void invalidPoolQueueTimeoutAndBufferLimitsFailBinding() {
        for (var entry : Map.of("max-pools", "257", "connections-per-pool", "65",
                "pending-acquires", "1025", "acquire-timeout", "31s", "max-in-memory-bytes", "1023").entrySet()) {
            assertThrows(BeansException.class,
                    () -> bind(Map.of("arte.ai-new-http.tool-http." + entry.getKey(), entry.getValue())));
        }
    }

    @Test
    public void unknownGroupsAndFieldsCannotSilentlyChangeDefaults() {
        assertThrows(BeansException.class, () -> bind(Map.of("arte.ai-new-http.shared.max-pools", "1")));
        assertThrows(BeansException.class, () -> bind(Map.of("arte.ai-new-http.model.allowed-origins[0]", "https://example.invalid")));
    }
}
