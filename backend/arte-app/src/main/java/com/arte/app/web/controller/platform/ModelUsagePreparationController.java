package com.arte.app.web.controller.platform;

import com.arte.ainew.api.model.ModelUsagePreparationService;
import com.arte.ainew.pojo.model.ModelUsageChecks;
import com.arte.ainew.pojo.model.ModelUsagePreparation;
import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.web.platform.ModelResourcePayload;
import com.arte.app.web.platform.ModelUsagePreparationPayload.Request;
import com.arte.app.web.platform.PlatformHttpContext;
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
 * 模型使用准备入口
 * <p>
 * 沿用真实会话、公共门控及成功命令回执，不自动应用聊天配置。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/02 21:30 ✾
 */
@RestController
@RequestMapping("/ai-new/model-resource")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte.ai-new-model-publication", name = "enabled", havingValue = "true")
public class ModelUsagePreparationController {

    private final PlatformAuthenticatedExecutor executor;
    private final ModelUsagePreparationService preparation;

    public ModelUsagePreparationController(PlatformAuthenticatedExecutor executor, ModelUsagePreparationService preparation) {
        this.executor = executor;
        this.preparation = preparation;
    }

    @PostMapping("/getModelUsagePreparation")
    public Mono<ResultContext<ModelUsagePreparation>> status(Authentication auth, @Valid @RequestBody Request request, Locale locale) {
        return executor.call(auth, actor -> preparation.status(actor, request.scope(), ModelResourcePayload.ref(request.scope(), request.resourceId()), request.binding()))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/getModelUsageChecks")
    public Mono<ResultContext<ModelUsageChecks>> checks(Authentication auth, @Valid @RequestBody Request request, Locale locale) {
        return executor.call(auth, actor -> preparation.checks(actor, request.scope(),
                        ModelResourcePayload.ref(request.scope(), request.resourceId()), request.binding()))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/prepareModelUsage")
    public Mono<ResultContext<CommandReceipt<ModelUsagePreparation>>> prepare(Authentication auth, @Valid @RequestBody Request request,
                                                                              @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        CommandMeta meta = new CommandMeta(key, PlatformHttpContext.traceId(http));
        return executor.call(auth, actor -> preparation.prepare(actor, meta, request.scope(), ModelResourcePayload.ref(request.scope(), request.resourceId()), request.binding()))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }
}
