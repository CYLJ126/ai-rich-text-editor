package com.arte.app.web.controller.platform;

import com.arte.ainew.api.model.ModelDraftService;
import com.arte.ainew.pojo.model.ModelDrafts;
import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.web.platform.ModelPublicationPayload.OptionsRequest;
import com.arte.app.web.platform.ModelResourcePayload;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Locale;

/**
 * 普通模型管理者读取受信方案
 * <p>
 * 不要求其具有平台审核或预算资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/03 12:50 ✾
 */
@RestController
@RequestMapping("/ai-new/model-draft")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte.ai-new-model-publication", name = "enabled", havingValue = "true")
public class ReviewedModelPlanController {

    private final PlatformAuthenticatedExecutor executor;
    private final ModelDraftService publications;

    public ReviewedModelPlanController(PlatformAuthenticatedExecutor executor, ModelDraftService publications) {
        this.executor = executor;
        this.publications = publications;
    }

    @PostMapping("/listReviewedModelPlans")
    public Mono<ResultContext<ModelDrafts.ReviewedPlanList>> options(Authentication auth, @Valid @RequestBody OptionsRequest request, Locale locale) {
        return executor.call(auth, actor -> publications.plans(actor, request.scope(), ModelResourcePayload.ref(request.scope(), request.resourceId()), request.cursor(), request.size()))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

}
