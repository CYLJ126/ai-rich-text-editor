package com.arte.app.web.controller.platform;

import com.arte.ainew.api.model.PersonalModelPublicationService;
import com.arte.ainew.pojo.model.PersonalModels;
import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.web.platform.ModelResourcePayload;
import com.arte.app.web.platform.PersonalModelPayload.OptionsRequest;
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

import java.util.List;
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
    private final PersonalModelPublicationService publications;

    public ReviewedModelPlanController(PlatformAuthenticatedExecutor executor, PersonalModelPublicationService publications) {
        this.executor = executor;
        this.publications = publications;
    }

    @PostMapping("/listReviewedModelPlans")
    public Mono<ResultContext<List<PersonalModels.Option>>> options(Authentication auth, @Valid @RequestBody OptionsRequest request, Locale locale) {
        return executor.call(auth, actor -> publications.options(actor, request.scope(), ModelResourcePayload.ref(request.scope(), request.resourceId())))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

}
