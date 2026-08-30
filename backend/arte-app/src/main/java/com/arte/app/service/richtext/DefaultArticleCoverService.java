package com.arte.app.service.richtext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Provides built-in, topic-neutral covers for articles that do not have one yet.
 */
@Service
public class DefaultArticleCoverService {

    private static final String COVER_PATH = "/article-covers/";
    private static final List<String> COVER_FILENAMES = List.of(
            "ideas-in-motion.webp",
            "open-road.webp",
            "connected-knowledge.webp",
            "structured-space.webp"
    );

    private final String contextPath;

    public DefaultArticleCoverService(@Value("${server.servlet.context-path:}") String contextPath) {
        this.contextPath = normalizeContextPath(contextPath);
    }

    public String randomCoverUrl() {
        int index = ThreadLocalRandom.current().nextInt(COVER_FILENAMES.size());
        return contextPath + COVER_PATH + COVER_FILENAMES.get(index);
    }

    private String normalizeContextPath(String path) {
        if (path == null || path.isBlank() || "/".equals(path.trim())) {
            return "";
        }
        String normalized = path.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
