package com.arte.app.web.controller.platform;

import com.arte.ainew.api.model.ModelAclMigrationService;
import com.arte.ainew.api.model.ModelResourceService;
import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.pojo.model.ModelPublication;
import com.arte.ainew.pojo.model.ModelResourceCommands;
import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.web.platform.ModelResourcePayload;
import com.arte.app.web.platform.ModelResourcePayload.*;
import com.arte.app.web.platform.PlatformHttpContext;
import com.arte.app.web.platform.PlatformHttpViews;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.PageResult;
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
 * 稳定模型资源管理及显式 ACL 范围切换
 * <p>
 * 只采用受信已有发布，不创建连接、凭据、预算或执行身份。
 * 公开版本视图与内部发布快照分离，所有写入复用既有应用服务的原事务、CAS、幂等及审计。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:00 ✾
 */
@RestController
@RequestMapping("/ai-new/model-resource")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "platform.identity.enabled", "platform.persistence.enabled", "platform.workspace.enabled"}, havingValue = "true")
public class ModelResourceController {

    private final PlatformAuthenticatedExecutor executor;
    private final ModelResourceService models;
    private final ModelAclMigrationService migration;

    public ModelResourceController(PlatformAuthenticatedExecutor executor, ModelResourceService models, ModelAclMigrationService migration) {
        this.executor = executor;
        this.models = models;
        this.migration = migration;
    }

    /**
     * 返回当前主体在管理空间可创建的归属；不以菜单或前端角色推断资格。
     */
    @PostMapping("/getModelCreationOptions")
    public Mono<ResultContext<List<ModelResourceCommands.ModelKind>>> getModelCreationOptions(Authentication auth,
                                                                                              @Valid @RequestBody ScopeRequest request, Locale locale) {
        return executor.call(auth, actor -> models.creationOptions(actor, request.scope()))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    /**
     * 服务端生成模型身份，并在同一事务登记私有 ACL，不自动加入运行候选清单。
     */
    @PostMapping("/createModelResource")
    public Mono<ResultContext<CommandReceipt<Resource>>> createModelResource(Authentication auth, @Valid @RequestBody CreateRequest request,
                                                                             @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        ModelResourceCommands.Create command = new ModelResourceCommands.Create(meta(key, http), request.scope(), request.name(), request.modelKind());
        return executor.call(auth, actor -> PlatformHttpViews.receipt(models.create(actor, command), value -> Resource.from(value).withActions(models.actions(actor, value.managementScope(), value.resource()))))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/getModelResource")
    public Mono<ResultContext<Resource>> getModelResource(Authentication auth, @Valid @RequestBody GetRequest request, Locale locale) {
        return executor.call(auth, actor -> Resource.from(models.get(actor, request.scope(), ModelResourcePayload.ref(request.scope(), request.resourceId())))
                        .withActions(models.actions(actor, request.scope(), ModelResourcePayload.ref(request.scope(), request.resourceId()))))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    /**
     * 只返回精确版本定位与模型元数据，不返回连接地址、凭据引用或内部快照。
     */
    @PostMapping("/listModelResourceVersions")
    public Mono<ResultContext<PageResult<Version>>> listModelResourceVersions(Authentication auth, @Valid @RequestBody VersionsRequest request, Locale locale) {
        return executor.call(auth, actor -> PlatformHttpViews.page(models.versions(actor, request.scope(), ModelResourcePayload.ref(request.scope(), request.resourceId()),
                        new PageQuery(request.current(), request.size())), Version::from))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    /**
     * 采用已有审核发布，同时核验 model:publish 和当前平台配置管理资格。
     */
    @PostMapping("/associateModelResourceVersion")
    public Mono<ResultContext<CommandReceipt<Version>>> associateModelResourceVersion(Authentication auth, @Valid @RequestBody AssociateRequest request,
                                                                                      @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        ModelResourceCommands.Associate command = new ModelResourceCommands.Associate(meta(key, http), request.scope(), ModelResourcePayload.ref(request.scope(), request.resourceId()),
                request.expectedVersion(), request.binding(), ModelPublication.Source.valueOf(request.source()), request.catalogRevision());
        return executor.call(auth, actor -> PlatformHttpViews.receipt(models.associate(actor, command), Version::from))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    /**
     * 停用保留模型身份及历史版本；已迁移空间的使用资格将由执行边界重新拒绝。
     */
    @PostMapping("/disableModelResource")
    public Mono<ResultContext<CommandReceipt<Resource>>> disableModelResource(Authentication auth, @Valid @RequestBody DisableRequest request,
                                                                              @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        ModelResourceCommands.Disable command = new ModelResourceCommands.Disable(meta(key, http), request.scope(), ModelResourcePayload.ref(request.scope(), request.resourceId()), request.expectedVersion());
        return executor.call(auth, actor -> PlatformHttpViews.receipt(models.disable(actor, command), Resource::from))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    /**
     * 当前空间和平台配置管理者查询显式切换状态，不自动写入迁移记录。
     */
    @PostMapping("/getModelAclScope")
    public Mono<ResultContext<AclScope>> getModelAclScope(Authentication auth, @Valid @RequestBody ScopeRequest request, Locale locale) {
        return executor.call(auth, actor -> migration.get(actor, request.scope()).map(AclScope::from).orElseGet(() -> AclScope.absent(request.scope())))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    /**
     * 首次显式激活或完整更新精确绑定清单；不提供删除清单或回退旧 Grant 的入口。
     */
    @PostMapping("/updateModelAclScope")
    public Mono<ResultContext<CommandReceipt<AclScope>>> updateModelAclScope(Authentication auth, @Valid @RequestBody UpdateScopeRequest request,
                                                                             @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        ModelAclMigrationService.Migrate command = new ModelAclMigrationService.Migrate(meta(key, http), request.scope(), AiEntitlement.Source.valueOf(request.source()), request.bindingSet(), request.expectedVersion());
        return executor.call(auth, actor -> PlatformHttpViews.receipt(migration.migrate(actor, command), AclScope::from))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    private static CommandMeta meta(String key, HttpServletRequest request) {
        return new CommandMeta(key, PlatformHttpContext.traceId(request));
    }
}
