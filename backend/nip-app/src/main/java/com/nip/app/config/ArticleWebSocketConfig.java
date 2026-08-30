package com.nip.app.config;

import com.nip.app.web.websocket.ArticleVersionWebSocketHandler;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class ArticleWebSocketConfig implements WebSocketConfigurer {

    @Resource
    private ArticleVersionWebSocketHandler articleVersionWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(articleVersionWebSocketHandler, "/webSocket/article-version")
                .setAllowedOriginPatterns("*");
    }
}
