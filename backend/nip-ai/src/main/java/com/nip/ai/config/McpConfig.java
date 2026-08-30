package com.nip.ai.config;

import cn.hutool.core.util.StrUtil;
import io.modelcontextprotocol.common.McpTransportContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 配置
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/8/27 20:14 ✾
 **/
@Slf4j
@Configuration
public class McpConfig {

    @Bean
    public WebMvcStreamableServerTransportProvider transport() {
        return WebMvcStreamableServerTransportProvider.builder()
                .contextExtractor(serverRequest -> {
                    Map<String, Object> contextMap = new HashMap<>();
                    String authorization = serverRequest.headers().firstHeader("Authorization");
                    contextMap.put("authorization", StrUtil.nullToDefault(authorization, ""));
                    String host = serverRequest.headers().firstHeader("Host");
                    contextMap.put("host", StrUtil.nullToDefault(host, ""));
                    return McpTransportContext.create(contextMap);
                })
                .build();
    }
}
