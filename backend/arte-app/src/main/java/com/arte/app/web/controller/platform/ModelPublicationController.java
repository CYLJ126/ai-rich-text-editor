package com.arte.app.web.controller.platform;

import com.arte.ainew.api.model.ModelPublicationService;
import com.arte.ainew.pojo.model.ModelPublications;
import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.web.platform.ModelPublicationPayload.PublishRequest;
import com.arte.app.web.platform.ModelPublicationPayload.StatusRequest;
import com.arte.app.web.platform.ModelResourcePayload;
import com.arte.app.web.platform.PlatformHttpContext;
import com.arte.app.web.platform.PlatformHttpViews;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Locale;

/**
 * 实际模型发布 HTTP 入口
 * <p>
 * 沿用当前认证及模型动作权限，响应不包含内部连接或密钥内容。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 20:55 ✾
 */
@RestController
@RequestMapping("/ai-new/model-resource")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte.ai-new-model-publication", name = "enabled", havingValue = "true")
public class ModelPublicationController {

    private final PlatformAuthenticatedExecutor executor;
    private final ModelPublicationService publications;

    public ModelPublicationController(PlatformAuthenticatedExecutor executor, ModelPublicationService publications) {
        this.executor = executor;
        this.publications = publications;
    }

    @PostMapping("/publishModel")
    public Mono<ResultContext<CommandReceipt<ModelResourcePayload.Version>>> publish(Authentication auth, @Valid @RequestBody PublishRequest request,
                                                                                     @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        CommandMeta meta = new CommandMeta(key, PlatformHttpContext.traceId(http));
        return executor.call(auth, actor -> PlatformHttpViews.receipt(publications.publish(actor, meta, request.scope(),
                        ModelResourcePayload.ref(request.scope(), request.resourceId()), request.expectedVersion(), request.draftVersion(),
                        request.credentialVersion(), request.validationId(), request.planRef(), request.catalogRevision()), ModelResourcePayload.Version::from))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/getModelPublicationStatus")
    public Mono<ResultContext<ModelPublications.Status>> status(Authentication auth, @Valid @RequestBody StatusRequest request, Locale locale) {
        return executor.call(auth, actor -> publications.status(actor, request.scope(), ModelResourcePayload.ref(request.scope(), request.resourceId()), request.binding()))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }
}
