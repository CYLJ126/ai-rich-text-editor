package com.arte.app.web.controller.platform;

import com.arte.ainew.api.model.ModelDraftService;
import com.arte.ainew.api.model.ModelResourceService;
import com.arte.ainew.pojo.model.ModelDrafts.*;
import com.arte.ainew.spi.model.ModelConnectionValidator;
import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.web.platform.ModelDraftPayload.*;
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

import java.util.List;
import java.util.Locale;

/**
 * 个人模型配置准备的 HTTP 入口，提供可读模型列表、连接模板、草稿、个人凭据维护和连接验证接口。
 * <p>
 * 沿用已认证会话，由 PlatformAuthenticatedExecutor 在有界数据库线程重新核验当前账号及稳定主体，
 * 再调用应用服务完成成员、资源和凭据责任检查；请求中的空间或资源 ID 只用于定位，不代表授权。
 * 所有写命令使用调用方提供的幂等键，追踪信息由服务端生成，公开响应不包含密钥、密文或内部验证依据。
 * <p>
 * 连接验证先受理并原子领取，领取事务提交后才执行网络探测，完成时再进入数据库线程复核和保存结果。
 * 重复请求只确认已有状态，不重复已经领取的探测；验证成功不发布模型、不调整预算，也不授予聊天执行资格。
 * 仅当模型管理、AI 及公共身份／持久化／空间开关全部启用时注册本控制器。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:55 ✾
 */
@RestController
@RequestMapping("/ai-new/model-draft")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte", name = {"ai-new-model-management.enabled", "ai-new.enabled", "platform.identity.enabled",
        "platform.persistence.enabled", "platform.workspace.enabled"}, havingValue = "true")
public class ModelDraftController {

    private final PlatformAuthenticatedExecutor executor;
    private final ModelDraftService service;
    private final ModelResourceService models;
    private final ModelConnectionValidator validator;

    public ModelDraftController(PlatformAuthenticatedExecutor executor, ModelDraftService service, ModelConnectionValidator validator, ModelResourceService models) {
        this.executor = executor;
        this.service = service;
        this.models = models;
        this.validator = validator;
    }

    /**
     * 查询本批可读模型元数据；允许空列表带后续游标，不返回隐藏模型总数。
     */
    @PostMapping("/listModelResources")
    public Mono<ResultContext<ModelListView>> list(Authentication auth, @Valid @RequestBody ListRequest request, Locale locale) {
        return executor.call(auth, actor -> {
            ModelList page = service.list(actor, request.scope(), request.cursor(), request.size());
            return new ModelListView(page.records().stream().map(model -> ModelResourcePayload.Resource.from(model)
                    .withActions(models.actions(actor, request.scope(), model.resource()))).toList(), page.nextCursor());
        }).map(value -> ok(value, locale));
    }

    /**
     * 返回当前范围允许选择的审核模板引用、供应商及协议，不返回连接地址或平台密钥。
     */
    @PostMapping("/listConnectionTemplates")
    public Mono<ResultContext<List<TemplateView>>> templates(Authentication auth, @Valid @RequestBody ModelResourcePayload.ScopeRequest request, Locale locale) {
        return executor.call(auth, actor -> service.templates(actor, request.scope())).map(v -> ok(v, locale));
    }

    /**
     * 读取当前配置草稿及脱敏凭据状态；模型身份存在但尚未保存草稿时 draft 为空。
     */
    @PostMapping("/getModelDraft")
    public Mono<ResultContext<DraftView>> get(Authentication auth, @Valid @RequestBody ModelResourcePayload.GetRequest request, Locale locale) {
        return executor.call(auth, actor -> service.get(actor, request.scope(), ModelResourcePayload.ref(request.scope(), request.resourceId()))).map(v -> ok(v, locale));
    }

    /**
     * 按草稿期望版本和幂等键保存待发布配置，不修改已有模型发布。
     */
    @PostMapping("/saveModelDraft")
    public Mono<ResultContext<CommandReceipt<Draft>>> save(Authentication auth, @Valid @RequestBody SaveRequest request,
                                                           @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        return executor.call(auth, actor -> service.save(actor, meta(key, http), request.scope(), ModelResourcePayload.ref(request.scope(), request.resourceId()),
                request.expectedVersion(), request.name(), request.settings().value())).map(v -> ok(v, locale));
    }

    /**
     * 首次提供或由原责任主体替换个人凭据；明文仅交给受控加密流程，回执只返回脱敏状态。
     */
    @PostMapping("/replaceModelCredential")
    public Mono<ResultContext<CommandReceipt<CredentialView>>> replace(Authentication auth, @Valid @RequestBody CredentialRequest request,
                                                                       @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        return executor.call(auth, actor -> request.credentialKind() == null
                ? service.replaceCredential(actor, meta(key, http), request.scope(), ModelResourcePayload.ref(request.scope(), request.resourceId()), request.expectedVersion(), request.token())
                : service.replaceCredential(actor, meta(key, http), request.scope(), ModelResourcePayload.ref(request.scope(), request.resourceId()),
                request.expectedVersion(), request.token(), CredentialKind.valueOf(request.credentialKind()))).map(v -> ok(v, locale));
    }

    /**
     * 由原责任主体撤销凭据；退出空间后也可撤销，但不因此恢复读取权限。
     */
    @PostMapping("/revokeModelCredential")
    public Mono<ResultContext<CommandReceipt<CredentialView>>> revoke(Authentication auth, @Valid @RequestBody RevokeRequest request,
                                                                      @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        return executor.call(auth, actor -> service.revokeCredential(actor, meta(key, http), request.scope(), ModelResourcePayload.ref(request.scope(), request.resourceId()),
                request.expectedVersion())).map(v -> ok(v, locale));
    }

    /**
     * 受理并尝试唯一领取指定版本的验证，在事务外探测后返回原始受理回执及独立的当前状态。
     * 未领取到探测输入时只查询已有记录，不自动重新发起网络请求。
     */
    @PostMapping("/validateModelDraft")
    public Mono<ResultContext<ValidationResponse>> validate(Authentication auth, @Valid @RequestBody ValidateRequest request,
                                                            @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        return executor.call(auth, actor -> service.requestValidation(actor, meta(key, http), request.scope(), ModelResourcePayload.ref(request.scope(), request.resourceId()),
                request.draftVersion(), request.credentialVersion())).flatMap(receipt ->
                executor.call(auth, actor -> service.claimValidation(actor, request.scope(), receipt.value().id())).flatMap(probe -> {
                    // 领取事务已经提交；网络探测期间不持有数据库锁。
                    Mono<Validation> result = probe.map(value -> validator.validate(value).switchIfEmpty(Mono.just(new ProbeResult(false, "REMOTE_UNAVAILABLE")))
                            .onErrorReturn(new ProbeResult(false, "REMOTE_UNAVAILABLE"))
                            .flatMap(r -> executor.call(auth, actor -> service.completeValidation(actor, request.scope(), receipt.value().id(), r)))).orElseGet(() -> executor.call(auth, actor -> service.validation(actor, request.scope(), receipt.value().id())));
                    return result.map(v -> ok(new ValidationResponse(PlatformHttpViews.receipt(receipt, ValidationView::from), ValidationView.from(v)), locale));
                }));
    }

    /**
     * 查询原请求者仍有权查看的验证状态，必要时标记失效，不发起网络探测。
     */
    @PostMapping("/getModelValidation")
    public Mono<ResultContext<ValidationView>> validation(Authentication auth, @Valid @RequestBody ValidationRequest request, Locale locale) {
        return executor.call(auth, actor -> service.validation(actor, request.scope(), request.validationId())).map(v -> ok(ValidationView.from(v), locale));
    }

    private static CommandMeta meta(String key, HttpServletRequest http) {
        return new CommandMeta(key, PlatformHttpContext.traceId(http));
    }

    private static <T> ResultContext<T> ok(T value, Locale locale) {
        return ResultContext.success(value, ResultCodeEnum.SUCCESS, locale);
    }
}
