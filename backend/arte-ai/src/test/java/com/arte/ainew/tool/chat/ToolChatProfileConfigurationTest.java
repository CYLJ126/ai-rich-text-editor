package com.arte.ainew.tool.chat;

import com.arte.ainew.config.NewAiToolChatProperties;
import org.junit.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.*;

/**
 * 验证 profile 经 Maven 过滤后真实进入运行时；不创建 Worker、连接数据库或读取凭据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 09:00 ✾
 */
public class ToolChatProfileConfigurationTest {
    private static final String PREFIX = "arte.ai-new-tool-chat";

    private Properties profile(String name) throws IOException {
        var result = new Properties();
        try (var input = Files.newInputStream(Path.of("../profile", name))) {
            result.load(input);
        }
        return result;
    }

    private StandardEnvironment runtime() throws IOException {
        var properties = new Properties();
        try (var input = new ClassPathResource("ainew/config/application.properties").getInputStream()) {
            properties.load(input);
        }
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new PropertiesPropertySource("filtered-runtime", properties));
        return environment;
    }

    @Test
    public void sharedDefaultsDoNotOpenToolChatOrStartItsWorker() throws Exception {
        var defaults = profile("common.properties");
        assertEquals("false", defaults.getProperty(PREFIX + ".enabled"));
        assertEquals("false", defaults.getProperty(PREFIX + ".worker-enabled"));
    }

    @Test
    public void allProfileSettingsReachTheFilteredRuntimeAndBind() throws Exception {
        var expected = profile("common.properties");
        expected.putAll(profile("app.properties"));
        var environment = runtime();
        for (var suffix : List.of("enabled", "worker-enabled", "poll-interval", "lease", "batch-size")) {
            String key = PREFIX + "." + suffix;
            assertEquals(key, expected.getProperty(key), environment.getProperty(key));
            assertFalse(environment.getRequiredProperty(key).contains("@"));
        }
        var bound = Binder.get(environment).bind(PREFIX, NewAiToolChatProperties.class).get();
        assertEquals(Boolean.parseBoolean(expected.getProperty(PREFIX + ".enabled")), bound.enabled());
        assertEquals(Boolean.parseBoolean(expected.getProperty(PREFIX + ".worker-enabled")), bound.workerEnabled());
    }

    @Test
    public void explicitRuntimeOverrideCanEnableBothFlagsWithoutChangingSharedDefaults() throws Exception {
        var environment = runtime();
        environment.getPropertySources().addFirst(new MapPropertySource("explicit-deployment", Map.of(
                PREFIX + ".enabled", "true", PREFIX + ".worker-enabled", "true")));
        var bound = Binder.get(environment).bind(PREFIX, NewAiToolChatProperties.class).get();
        assertTrue(bound.enabled());
        assertTrue(bound.workerEnabled());
        assertEquals(Duration.ofSeconds(1), bound.pollInterval());
        assertEquals(Duration.ofSeconds(45), bound.lease());
        assertEquals(8, bound.batchSize());
    }
}
