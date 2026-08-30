package com.arte.app.service.richtext;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultArticleCoverServiceTest {

    private static final Set<String> FILENAMES = Set.of(
            "ideas-in-motion.webp",
            "open-road.webp",
            "connected-knowledge.webp",
            "structured-space.webp"
    );

    @Test
    void returnsAConfiguredCoverWithNormalizedContextPath() {
        DefaultArticleCoverService service = new DefaultArticleCoverService("arte/");

        for (int i = 0; i < 50; i++) {
            String url = service.randomCoverUrl();
            assertTrue(url.startsWith("/arte/article-covers/"));
            assertTrue(FILENAMES.contains(url.substring(url.lastIndexOf('/') + 1)));
        }
    }

    @Test
    void supportsApplicationsWithoutAContextPath() {
        DefaultArticleCoverService service = new DefaultArticleCoverService("/");

        assertTrue(service.randomCoverUrl().startsWith("/article-covers/"));
    }
}
