package com.arte.app.ainew;

import com.arte.ai.api.context.RagContextService;
import com.arte.ai.api.context.ResourceContextService;
import com.arte.app.api.richtext.ArticleService;
import com.arte.app.service.richtext.ArticleContextQueryService;
import com.arte.app.service.richtext.ArticleRetrievalQueryService;
import com.arte.base.api.security.AuthorizationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"arte.ai-new.chat.enabled", "arte.ai-new.chat.retrieval-enabled"}, havingValue = "true")
@Import(NewResourceContextConfiguration.class)
public class NewRagConfiguration {
    @Bean public ArticleRetrievalQueryService newArticleRetrievalQuery(JdbcTemplate jdbc, ArticleService articles, ArticleContextQueryService texts) {
        return new ArticleRetrievalQueryService(jdbc, articles, texts);
    }
    @Bean public ArticleResourceRetrievalAdapter newArticleRetrieval(ArticleRetrievalQueryService articles, AuthorizationService authorization) {
        return new ArticleResourceRetrievalAdapter(articles, authorization, Clock.systemUTC());
    }
    @Bean public RagContextService newRagContexts(ArticleResourceRetrievalAdapter provider, ResourceContextService contexts, @org.springframework.beans.factory.annotation.Value("${arte.ai-new.chat.context-max-bytes:8192}") int bytes) {
        return new RagContextService(provider, contexts, bytes);
    }
    @Bean public JdbcRetrievalPreviewStore newRetrievalPreviews(JdbcTemplate jdbc) { return new JdbcRetrievalPreviewStore(jdbc); }
}
