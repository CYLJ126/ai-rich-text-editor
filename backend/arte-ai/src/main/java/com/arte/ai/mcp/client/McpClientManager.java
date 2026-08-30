package com.arte.ai.mcp.client;

import com.arte.ai.pojo.mcp.McpServerConfig;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Client 管理
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/8/28 22:24 ✾
 **/
@Slf4j
@Service
public class McpClientManager {
    public static final String TONGYI_WANXIANG_CLIENT_NAME = "moda-tongyi";

    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("MCP服务初始化开始");

        // 魔搭 - 通义万相文生图MCP服务初始化
        McpServerConfig tongyi = new McpServerConfig(
                TONGYI_WANXIANG_CLIENT_NAME,
                "https://mcp.api-inference.modelscope.net",
                "/69acfdea215444/mcp",
                Map.of()
        );
        clients.put(TONGYI_WANXIANG_CLIENT_NAME, createClient(tongyi));

        log.info("MCP服务初始化完成");
    }

    public McpSyncClient connect(McpServerConfig config) {
        return clients.computeIfAbsent(config.name(), key -> createClient(config));
    }

    private McpSyncClient createClient(McpServerConfig config) {
        var builder = HttpClientStreamableHttpTransport
                .builder(config.url())
                .endpoint(config.endpoint() == null || config.endpoint().isBlank() ? "/mcp" : config.endpoint());
        if (config.headers() != null && !config.headers().isEmpty()) {
            builder.httpRequestCustomizer(
                    (request, method, endpoint, body, context) -> {
                        log.debug("MCP request method={}, endpoint={}, body={}", method, endpoint, body);
                        config.headers().forEach(request::header);
                    }
            );
        }
        var transport = builder.build();
        var client = McpClient
                .sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        client.initialize();
        return client;
    }

    public McpSyncClient get(String name) {
        return clients.get(name);
    }

}
