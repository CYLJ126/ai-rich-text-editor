package com.arte.app.ainew;

import com.arte.ai.context.ResourceContextValues;
import com.arte.ai.conversation.ChatValues;
import com.arte.ai.model.context.ContextFragment;
import com.arte.ai.model.context.ResourceRetrievalRequest;
import com.arte.ai.spi.business.ResourceRetrievalProvider;
import com.arte.app.service.richtext.ArticleRetrievalQueryService;
import com.arte.base.api.security.AuthorizationService;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.resource.SourceRef;
import com.arte.base.model.security.AuthorizationRequest;
import com.arte.base.model.security.CommonResourceAction;
import com.arte.base.model.security.PolicyOutcome;

import java.time.Clock;
import java.util.*;

/**
 * 新 RAG 提供者；固定 MySQL 版本，防止 ES 过期、返回范围外文章或空范围退化为全库查询。
 */
public final class ArticleResourceRetrievalAdapter implements ResourceRetrievalProvider {
    private final ArticleRetrievalQueryService articles;
    private final AuthorizationService authorization;
    private final Clock clock;

    public ArticleResourceRetrievalAdapter(ArticleRetrievalQueryService articles, AuthorizationService authorization, Clock clock) {
        this.articles = articles;
        this.authorization = authorization;
        this.clock = clock;
    }

    public List<ArticleRetrievalQueryService.Metadata> authorized(ExecutionContext viewer, ResourceRetrievalRequest selection) {
        var candidates = articles.candidates(viewer.scope(), selection.articleIds());
        var selected = candidates.stream().filter(value -> selection.mode() == ResourceRetrievalRequest.Mode.ARTICLE_LIBRARY
                || selection.articleIds().contains(value.id())).toList();
        if (selection.mode() != ResourceRetrievalRequest.Mode.ARTICLE_LIBRARY && selected.size() != selection.articleIds().size())
            throw ChatValues.failure(CommonErrorCode.NOT_FOUND, "rag-article");
        var allowed = new ArrayList<ArticleRetrievalQueryService.Metadata>();
        for (var value : selected) {
            boolean readable = true;
            for (var action : List.of(CommonResourceAction.READ, CommonResourceAction.AI_PROCESS)) {
                var request = AuthorizationRequest.of(viewer, value.resource(), action);
                var decision = authorization.evaluate(request);
                if (!request.equals(decision.request()))
                    throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "rag-authorization");
                if (decision.policy().outcome() == PolicyOutcome.INDETERMINATE)
                    throw ChatValues.failure(CommonErrorCode.POLICY_UNAVAILABLE, "rag-authorization-unavailable");
                if (!decision.policy().isAllowedAt(clock.instant())) readable = false;
            }
            if (readable) allowed.add(value);
            else if (selection.mode() != ResourceRetrievalRequest.Mode.ARTICLE_LIBRARY)
                throw ChatValues.failure(CommonErrorCode.UNAUTHORIZED, "rag-article");
        }
        return List.copyOf(allowed);
    }

    @Override
    public List<ContextFragment> retrieve(ExecutionContext viewer, ResourceRetrievalRequest selection, String query) {
        if (selection.mode() == ResourceRetrievalRequest.Mode.NONE) return List.of();
        var allowed = authorized(viewer, selection);
        if (allowed.isEmpty()) return List.of();
        if (selection.mode() == ResourceRetrievalRequest.Mode.ARTICLE_FULL_TEXT) {
            var metadata = allowed.getFirst();
            var article = articles.fullText(metadata.id());
            if (!Objects.equals(metadata.version(), article.version()))
                throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "rag-article-version");
            if (article.text() == null || article.text().isBlank())
                throw ChatValues.failure(CommonErrorCode.UNSUPPORTED, "rag-article-text");
            var ref = new ResourceRef("ARTICLE", article.id(), article.version(), null, null, ResourceContextValues.textDigest(article.text()));
            return List.of(new ContextFragment("article-1", new SourceRef(ref, "article-1"), article.text(), false,
                    "已保存全文；标题：" + article.title()));
        }
        var byId = new HashMap<Integer, ArticleRetrievalQueryService.Metadata>();
        allowed.forEach(value -> byId.put(Integer.parseInt(value.id()), value));
        var hits = articles.search(query, byId.keySet(), selection.semanticSearch(), selection.maxResults());
        var fragments = new ArrayList<ContextFragment>();
        var seen = new HashSet<String>();
        for (var hit : hits) {
            if (hit == null || hit.getArticleId() == null || !byId.containsKey(hit.getArticleId())
                    || hit.getArticleMeta() != null && !Objects.equals(hit.getArticleId(), hit.getArticleMeta().getArticleId()))
                throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "rag-search-scope");
            var metadata = byId.get(hit.getArticleId());
            if (hit.getArticleMeta() == null || hit.getArticleMeta().getRowVersion() == null
                    || !metadata.version().equals(hit.getArticleMeta().getRowVersion().toString()))
                throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "rag-index-stale");
            if (hit.getChunkId() == null || hit.getChunkId().isBlank())
                throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "rag-chunk-reference");
            String content = hit.getContentWithBreadcrumb();
            if (content == null || content.isBlank()) content = hit.getContent();
            if (content == null || content.isBlank()) continue;
            if (!seen.add(metadata.id() + ":" + hit.getChunkId())) continue;
            String citation = "article-" + (fragments.size() + 1);
            // ES chunk ID 是领域范围引用，不能假称为正文 UTF-16 偏移。
            var ref = new ResourceRef("ARTICLE", metadata.id(), metadata.version(), null, "es-chunk:" + hit.getChunkId(), ResourceContextValues.textDigest(content));
            fragments.add(new ContextFragment(citation, new SourceRef(ref, citation), content, true,
                    "ES 检索片段；标题：" + metadata.title() + "；分块：" + hit.getChunkId()));
            if (fragments.size() == selection.maxResults()) break;
        }
        // 检索期间的删除、版本更新和撤权在返回内容前再检查一次。
        var currentVersions = new HashMap<String, String>();
        var usedIds = fragments.stream().map(fragment -> fragment.source().resource().resourceId()).distinct().toList();
        if (!usedIds.isEmpty())
            articles.candidates(viewer.scope(), usedIds).forEach(value -> currentVersions.put(value.id(), value.version()));
        for (var metadata : allowed) {
            if (fragments.stream().noneMatch(f -> f.source().resource().resourceId().equals(metadata.id()))) continue;
            if (!metadata.version().equals(currentVersions.get(metadata.id())))
                throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "rag-index-stale");
            authorization.requireAuthorized(AuthorizationRequest.of(viewer, metadata.resource(), CommonResourceAction.READ));
            authorization.requireAuthorized(AuthorizationRequest.of(viewer, metadata.resource(), CommonResourceAction.AI_PROCESS));
        }
        return List.copyOf(fragments);
    }
}
