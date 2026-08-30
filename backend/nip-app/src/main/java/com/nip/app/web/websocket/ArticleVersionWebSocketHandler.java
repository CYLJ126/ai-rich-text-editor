package com.nip.app.web.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nip.app.api.rbac.OnlineService;
import com.nip.app.api.rbac.TokenService;
import com.nip.app.mapper.richtext.ArticleMapper;
import com.nip.app.pojo.richtext.ArticleDto;
import com.nip.app.service.richtext.PermissionValidator;
import com.nip.core.pojo.UserContext;
import com.nip.core.pojo.UserOnlineInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ArticleVersionWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    @Resource
    private TokenService tokenService;
    @Resource
    private OnlineService onlineService;
    @Resource
    private PermissionValidator permissionValidator;
    @Resource
    private ArticleMapper articleMapper;

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode payload = objectMapper.readTree(message.getPayload());
        if (!"subscribe".equals(payload.path("type").asText())) {
            return;
        }

        String token = payload.path("token").asText();
        int articleId = payload.path("articleId").asInt();
        if (token.isBlank() || articleId <= 0 || !tokenService.verifyToken(token)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        UserOnlineInfo userInfo = onlineService.getOnlineInfo(token);
        if (userInfo == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        ArticleDto article = articleMapper.getByIdUnfiltered(articleId);
        if (article == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        UserContext.setUserOnlineInfo(userInfo);
        try {
            if (!permissionValidator.canRead(article)) {
                session.close(CloseStatus.NOT_ACCEPTABLE);
                return;
            }
        } finally {
            UserContext.clear();
        }

        subscriptions.put(session.getId(), new Subscription(session, articleId, userInfo.getUserName()));
        sendVersion(session, articleId, article.getRowVersion(), article.getUpdateBy());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, @NonNull CloseStatus status) {
        subscriptions.remove(session.getId());
    }

    public void publishVersion(Integer articleId, Integer rowVersion, String updateBy) {
        if (articleId == null || rowVersion == null) {
            return;
        }
        subscriptions.forEach((sessionId, subscription) -> {
            if (subscription.articleId() != articleId) {
                return;
            }
            WebSocketSession session = subscription.session();
            if (session == null || !session.isOpen()) {
                subscriptions.remove(sessionId);
                return;
            }
            try {
                sendVersion(session, articleId, rowVersion, updateBy);
            } catch (IOException e) {
                log.warn("推送文章版本失败，articleId={}，userName={}", articleId, subscription.userName(), e);
            }
        });
    }

    private void sendVersion(WebSocketSession session, Integer articleId, Integer rowVersion, String updateBy)
            throws IOException {
        String payload = objectMapper.writeValueAsString(Map.of(
                "type", "article-version",
                "articleId", articleId,
                "rowVersion", rowVersion == null ? 0 : rowVersion,
                "updateBy", updateBy == null ? "" : updateBy));
        synchronized (session) {
            session.sendMessage(new TextMessage(payload));
        }
    }

    private record Subscription(WebSocketSession session, int articleId, String userName) {
    }
}
