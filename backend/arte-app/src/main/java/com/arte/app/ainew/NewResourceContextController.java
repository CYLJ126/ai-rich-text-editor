package com.arte.app.ainew;

import com.arte.ai.context.ResourceContextValues;
import com.arte.ai.conversation.ChatValues;
import com.arte.app.security.bridge.ExecutionContextFactory;
import com.arte.app.service.richtext.ArticleContextQueryService;
import com.arte.base.api.security.AuthorizationService;
import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.security.AuthorizationRequest;
import com.arte.base.model.security.CommonResourceAction;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/** 授权后的文章文本坐标与固定引用，供用户选择全文或摘录；不派发模型。 */
@RestController
@RequestMapping("/api/ai-new/context")
@ConditionalOnProperty(name = "arte.ai-new.action.enabled", havingValue = "true")
@PreAuthorize("isAuthenticated()")
public class NewResourceContextController {
    public record ArticleContextInfo(ResourceRef resource, String title, String text, String representation, int utf16Length) { }
    private final ArticleContextQueryService articles;
    private final AuthorizationService authorization;
    private final ExecutionContextFactory contexts;
    private final ConfiguredModelDefinitions definitions;
    private final String application;

    public NewResourceContextController(ArticleContextQueryService articles, AuthorizationService authorization, ExecutionContextFactory contexts,
                                         ConfiguredModelDefinitions definitions, @Value("${arte.ai-new.model.application-id:ai-new-model}") String application) {
        this.articles = articles; this.authorization = authorization; this.contexts = contexts; this.definitions = definitions; this.application = application;
    }

    @GetMapping("/articles/{id}")
    public ArticleContextInfo article(HttpServletRequest http, @PathVariable String id, @RequestParam String tenantId, @RequestParam String workspaceId) {
        var resource = ResourceRef.current("ARTICLE", id);
        var read = Set.of(CommonResourceAction.READ.code());
        var viewer = contexts.create(http, tenantId, workspaceId, application, definitions.bindingRef().definitionId(), read, Map.of(resource, read));
        authorization.requireAuthorized(AuthorizationRequest.of(viewer, resource, CommonResourceAction.READ));
        var article = articles.find(id).orElseThrow(() -> ChatValues.failure(CommonErrorCode.NOT_FOUND, "article-context"));
        if (article.version() == null || article.text() == null) throw ChatValues.failure(CommonErrorCode.UNSUPPORTED, "article-context-text-projection");
        authorization.requireAuthorized(AuthorizationRequest.of(viewer, resource, CommonResourceAction.READ));
        return new ArticleContextInfo(new ResourceRef("ARTICLE", id, article.version(), null, null, ResourceContextValues.textDigest(article.text())),
                article.title(), article.text(), "stored-plain-text", article.text().length());
    }
    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ExecutionError> failure(BaseException failure) { return NewAiErrorResponses.failure(failure); }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ExecutionError> denied() { return NewAiErrorResponses.denied(); }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<ExecutionError> invalid(Exception failure) { return NewAiErrorResponses.invalid(); }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ExecutionError> unavailable() { return NewAiErrorResponses.unavailable(); }
}
