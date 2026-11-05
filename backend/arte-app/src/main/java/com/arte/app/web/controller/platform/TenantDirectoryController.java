package com.arte.app.web.controller.platform;

import com.arte.app.platform.workspace.ReactiveTenantQueries;
import com.arte.app.platform.workspace.TenantDirectoryBrowser;
import com.arte.app.web.platform.PlatformHttpContext;
import com.arte.app.web.platform.StrictPlatformRequest;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.enums.workspace.TenantTypeEnum;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.workspace.TenantBrowseQuery;
import com.arte.core.pojo.workspace.TenantCommands;
import com.arte.core.pojo.workspace.TenantDirectoryViews;
import com.arte.core.pojo.workspace.WorkspaceViews;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Locale;

/**
 * 独立于空间目录及使用偏好的租户查询及基本治理入口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 13:11 ✾
 */
@RestController
@RequestMapping("/workspace")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte.platform.workspace", name = "enabled", havingValue = "true")
public class TenantDirectoryController {

    private final ReactiveTenantQueries queries;

    public TenantDirectoryController(ReactiveTenantQueries queries) {
        this.queries = queries;
    }

    public record BrowseRequest(@Size(min = 36, max = 36) String cursor, @Min(1) @Max(100) Integer size,
                                @Size(max = 100) String search, @Min(0) @Max(1) Integer type)
            implements StrictPlatformRequest {
        public BrowseRequest {
            size = size == null ? 20 : size;
        }

        TenantBrowseQuery query() {
            TenantTypeEnum filter = type == null ? null : switch (type) {
                case 0 -> TenantTypeEnum.PERSONAL;
                case 1 -> TenantTypeEnum.ORGANIZATION;
                default -> throw new IllegalArgumentException("Invalid tenant type");
            };
            return new TenantBrowseQuery(size, search, filter);
        }
    }

    public record GetRequest(@NotBlank @Size(max = 256) String tenantId) implements StrictPlatformRequest {
    }

    public record CreationOptionsRequest() implements StrictPlatformRequest {
    }

    @PostMapping("/browseAccessibleTenants")
    public Mono<ResultContext<TenantDirectoryBrowser.Page>> browseAccessibleTenants(
            Authentication authentication, @Valid @RequestBody BrowseRequest request, Locale locale) {
        return queries.browse(authentication, request.cursor(), request.query())
                .map(page -> ResultContext.success(page, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/getTenant")
    public Mono<ResultContext<TenantDirectoryViews.Detail>> getTenant(
            Authentication authentication, @Valid @RequestBody GetRequest request, Locale locale) {
        return queries.detail(authentication, request.tenantId())
                .map(detail -> ResultContext.success(detail, ResultCodeEnum.SUCCESS, locale));
    }

    public record UpdateRequest(@NotBlank @Size(max = 256) String tenantId,
                                @NotNull @Min(0) Long expectedTenantVersion,
                                @NotBlank @Size(max = 100) String name) implements StrictPlatformRequest {
    }

    public record CreateRequest(@NotBlank @Size(max = 100) String name) implements StrictPlatformRequest {
    }

    @PostMapping("/updateTenant")
    public Mono<ResultContext<CommandReceipt<WorkspaceViews.TenantView>>> updateTenant(Authentication authentication,
                                                                                       @Valid @RequestBody UpdateRequest request, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        TenantCommands.UpdateTenant command = new TenantCommands.UpdateTenant(new CommandMeta(key, PlatformHttpContext.traceId(http)),
                request.tenantId(), request.expectedTenantVersion(), request.name());
        return queries.updateTenant(authentication, command).map(receipt -> ResultContext.success(receipt, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/createOrganization")
    public Mono<ResultContext<CommandReceipt<WorkspaceViews.TenantView>>> createOrganization(Authentication authentication,
                                                                                             @Valid @RequestBody CreateRequest request, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        TenantCommands.CreateOrganization command = new TenantCommands.CreateOrganization(
                new CommandMeta(key, PlatformHttpContext.traceId(http)), request.name());
        return queries.createOrganization(authentication, command).map(receipt -> ResultContext.success(receipt, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/getTenantCreationOptions")
    public Mono<ResultContext<TenantDirectoryViews.CreationOptions>> getTenantCreationOptions(
            Authentication authentication, @Valid @RequestBody CreationOptionsRequest request, Locale locale) {
        return queries.creationOptions(authentication)
                .map(options -> ResultContext.success(options, ResultCodeEnum.SUCCESS, locale));
    }
}
