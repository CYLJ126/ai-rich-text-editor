package com.arte.app.ainew;

import com.arte.ai.context.ResourceContextValues;
import com.arte.ai.conversation.ChatValues;
import com.arte.ai.model.context.ContextFragment;
import com.arte.ai.model.context.ResourceContextSelection;
import com.arte.ai.spi.business.ResourceContextAdapter;
import com.arte.app.service.richtext.ArticleContextQueryService;
import com.arte.base.api.security.AuthorizationService;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.resource.SourceRef;
import com.arte.base.model.security.AuthorizationRequest;
import com.arte.base.model.security.CommonResourceAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 文章组合适配；先授权，再通过文章领域查询固定版本或验证用户草稿。 */
@Component
@ConditionalOnProperty(name = "arte.ai-new.action.enabled", havingValue = "true")
public final class ArticleResourceContextAdapter implements ResourceContextAdapter {
    private final ArticleContextQueryService articles;
    private final AuthorizationService authorization;
    public ArticleResourceContextAdapter(ArticleContextQueryService articles, AuthorizationService authorization) {
        this.articles = articles; this.authorization = authorization;
    }
    @Override public String resourceType() { return "ARTICLE"; }

    @Override
    public ContextFragment resolve(ExecutionContext viewer, ResourceContextSelection selection, String citationId) {
        var resource = selection.resource();
        if (!resourceType().equals(resource.resourceType())) throw ChatValues.failure(CommonErrorCode.UNSUPPORTED, "article-context-type");
        var source = new SourceRef(resource, citationId);
        authorize(viewer, source, false);
        if (resource.isDraft()) require(viewer, source, CommonResourceAction.EDIT);
        var article = articles.find(resource.resourceId()).orElseThrow(() -> ChatValues.failure(CommonErrorCode.NOT_FOUND, "article-context"));
        if (article.version() == null || !article.version().equals(resource.version()))
            throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "article-context-version");
        String text = resource.isDraft() ? selection.draftText() : article.text();
        if (text == null) throw ChatValues.failure(CommonErrorCode.UNSUPPORTED, "article-context-text-projection");
        validateUtf16(text);
        if (!resource.contentDigest().equals(ResourceContextValues.textDigest(text)))
            throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "article-context-content");
        int start = 0, end = text.length();
        if (resource.rangeRef() != null) {
            if (!resource.rangeRef().matches("utf16:(0|[1-9][0-9]*):(0|[1-9][0-9]*)")) throw new IllegalArgumentException("unsupported article range");
            var parts = resource.rangeRef().split(":"); start = Integer.parseInt(parts[1]); end = Integer.parseInt(parts[2]);
        }
        if (start < 0 || end <= start || end > text.length() || splitSurrogate(text, start) || splitSurrogate(text, end))
            throw new IllegalArgumentException("invalid article text range");
        String selected = text.substring(start, end);
        if (selected.isBlank()) throw new IllegalArgumentException("selected content is blank");
        boolean partial = start != 0 || end != text.length();
        var coverage = (resource.isDraft() ? "用户草稿" : "已保存正文") + (partial ? "选区" : "全文")
                + "；UTF-16 范围 [" + start + "," + end + ")，正文总长度 " + text.length();
        return new ContextFragment(citationId, source, selected, partial, coverage);
    }

    @Override
    public void authorize(ExecutionContext viewer, SourceRef source, boolean forEgress) {
        if (!resourceType().equals(source.resource().resourceType())) throw ChatValues.failure(CommonErrorCode.UNSUPPORTED, "article-context-type");
        require(viewer, source, CommonResourceAction.READ);
        require(viewer, source, CommonResourceAction.AI_PROCESS);
        if (forEgress) {
            require(viewer, source, CommonResourceAction.EGRESS);
            if (source.resource().isDraft()) require(viewer, source, CommonResourceAction.EDIT);
        }
    }

    private void require(ExecutionContext viewer, SourceRef source, CommonResourceAction action) {
        authorization.requireAuthorized(AuthorizationRequest.of(viewer, source.resource(), action));
    }
    private static boolean splitSurrogate(String text, int offset) {
        return offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1)) && Character.isLowSurrogate(text.charAt(offset));
    }
    private static void validateUtf16(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IllegalArgumentException("invalid UTF-16 content");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("invalid UTF-16 content");
        }
    }
}
