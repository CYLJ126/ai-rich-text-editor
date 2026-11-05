package com.arte.app.web.controller.platform;

import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.persistence.PlatformPersistenceExecutor;
import com.arte.app.web.platform.PlatformHttpContext;
import com.arte.app.web.platform.StrictPlatformRequest;
import com.arte.core.application.workspace.DefaultMembershipGovernanceService;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.Tenant;
import com.arte.core.pojo.workspace.TenantCommands;
import com.arte.core.pojo.workspace.Workspace;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import com.arte.core.pojo.workspace.WorkspaceViews.*;
import com.arte.core.spi.workspace.TenantStore;
import com.arte.core.spi.workspace.WorkspaceStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Locale;
import java.util.function.Function;

/**
 * 租户和空间成员治理及本人邀请箱的 HTTP 适配。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 21:08 ✾
 */
@RestController
@RequestMapping("/workspace")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte.platform", name = {"workspace.enabled", "membership.enabled"}, havingValue = "true")
public class MembershipGovernanceController {

    private final DefaultMembershipGovernanceService service;
    private final PlatformAuthenticatedExecutor executor;
    private final RbacIdentityProvider identities;
    private final TenantStore tenants;
    private final WorkspaceStore workspaces;

    public MembershipGovernanceController(DefaultMembershipGovernanceService service, RbacIdentityProvider identities,
                                          PlatformPersistenceExecutor persistence, TenantStore tenants, WorkspaceStore workspaces) {
        this.service = service;
        this.executor = new PlatformAuthenticatedExecutor(identities, persistence);
        this.identities = identities;
        this.tenants = tenants;
        this.workspaces = workspaces;
    }

    public record Target(@NotBlank @Size(max = 256) String tenantId, Scope scope) implements StrictPlatformRequest {
        public Target {
            if (scope != null && !scope.tenantId().equals(tenantId)) {
                throw new IllegalArgumentException("Scope mismatch");
            }
        }
    }

    public record Lookup(@NotBlank @Size(max = 256) String tenantId, Scope scope,
                         @NotBlank @Size(max = 100) String account) implements StrictPlatformRequest {
        public Lookup {
            if (scope != null && !scope.tenantId().equals(tenantId)) {
                throw new IllegalArgumentException("Scope mismatch");
            }
        }
    }

    public record InvitationTarget(String subjectId, String displayName) {
    }

    public record InvitationRow(String invitationId, String tenantId, Scope scope, String targetSubjectId,
                                String targetDisplayName, String targetName, Integer role, int state,
                                Instant expiresAt, long version) {
    }

    public record MemberRow(String memberId, String subjectId, String displayName, int role, int state, long version) {
    }

    public record TenantPage(@NotBlank @Size(max = 256) String tenantId, @Min(1) Long current,
                             @Min(1) @Max(100) Long size)
            implements StrictPlatformRequest {
    }

    public record SpacePage(@NotNull Scope scope, @Min(1) Long current, @Min(1) @Max(100) Long size)
            implements StrictPlatformRequest {
    }

    public record Page(@Min(1) Long current, @Min(1) @Max(100) Long size) implements StrictPlatformRequest {
    }

    public record TenantInvite(@NotBlank @Size(max = 256) String tenantId, @NotNull @Min(0) Long expectedTenantVersion,
                               @NotBlank @Size(max = 256) String targetSubjectId,
                               Instant expiresAt) implements StrictPlatformRequest {
    }

    public record SpaceInvite(@NotNull Scope scope, @NotNull @Min(0) Long expectedWorkspaceVersion,
                              @NotBlank @Size(max = 256) String targetSubjectId,
                              @NotNull @Min(2) @Max(3) Integer role,
                              Instant expiresAt) implements StrictPlatformRequest {
    }

    public record TenantRevoke(@NotBlank @Size(max = 256) String tenantId, @NotNull @Min(0) Long expectedTenantVersion,
                               @NotBlank @Size(max = 256) String invitationId,
                               @NotNull @Min(0) Long expectedInvitationVersion)
            implements StrictPlatformRequest {
    }

    public record SpaceRevoke(@NotNull Scope scope, @NotNull @Min(0) Long expectedWorkspaceVersion,
                              @NotBlank @Size(max = 256) String invitationId,
                              @NotNull @Min(0) Long expectedInvitationVersion)
            implements StrictPlatformRequest {
    }

    public record Accept(@NotBlank @Size(max = 256) String invitationId,
                         @NotNull @Min(0) Long expectedInvitationVersion)
            implements StrictPlatformRequest {
    }

    public record TenantRole(@NotBlank @Size(max = 256) String tenantId, @NotBlank @Size(max = 256) String memberId,
                             @NotNull @Min(0) Long expectedMemberVersion, @NotNull @Min(0) Long expectedTenantVersion,
                             @NotNull @Min(1) @Max(2) Integer role) implements StrictPlatformRequest {
    }

    public record SpaceRole(@NotNull Scope scope, @NotBlank @Size(max = 256) String memberId,
                            @NotNull @Min(0) Long expectedMemberVersion, @NotNull @Min(0) Long expectedWorkspaceVersion,
                            @NotNull @Min(1) @Max(3) Integer role) implements StrictPlatformRequest {
    }

    public record TenantRemove(@NotBlank @Size(max = 256) String tenantId, @NotBlank @Size(max = 256) String memberId,
                               @NotNull @Min(0) Long expectedMemberVersion, @NotNull @Min(0) Long expectedTenantVersion)
            implements StrictPlatformRequest {
    }

    public record SpaceRemove(@NotNull Scope scope, @NotBlank @Size(max = 256) String memberId,
                              @NotNull @Min(0) Long expectedMemberVersion,
                              @NotNull @Min(0) Long expectedWorkspaceVersion)
            implements StrictPlatformRequest {
    }

    public record Leave(@NotNull Scope scope, @NotNull @Min(0) Long expectedMemberVersion,
                        @NotNull @Min(0) Long expectedWorkspaceVersion) implements StrictPlatformRequest {
    }

    public record TenantTransfer(@NotBlank @Size(max = 256) String tenantId,
                                 @NotBlank @Size(max = 256) String targetMemberId,
                                 @NotNull @Min(0) Long expectedOwnerMemberVersion,
                                 @NotNull @Min(0) Long expectedTargetMemberVersion,
                                 @NotNull @Min(0) Long expectedTenantVersion) implements StrictPlatformRequest {
    }

    public record SpaceTransfer(@NotNull Scope scope, @NotBlank @Size(max = 256) String targetMemberId,
                                @NotNull @Min(0) Long expectedOwnerMemberVersion,
                                @NotNull @Min(0) Long expectedTargetMemberVersion,
                                @NotNull @Min(0) Long expectedWorkspaceVersion) implements StrictPlatformRequest {
    }

    private <T> Mono<ResultContext<T>> call(Authentication auth, Locale locale, Function<TrustedActor, T> work) {
        return executor.call(auth, work).map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    private static PageQuery page(Long current, Long size) {
        return new PageQuery(current == null ? 1 : current, size == null ? 20 : size);
    }

    private String name(String subject) {
        return identities.subjectName(subject).orElse(null);
    }

    @PostMapping("/getMembershipCapabilities")
    public Mono<ResultContext<MembershipCapabilities>> capabilities(Authentication auth, @Valid @RequestBody Target request, Locale locale) {
        return call(auth, locale, actor -> service.capabilities(actor, request.tenantId(), request.scope()));
    }

    @PostMapping("/findInvitationTarget")
    public Mono<ResultContext<InvitationTarget>> lookup(Authentication auth, @Valid @RequestBody Lookup request, Locale locale) {
        return call(auth, locale, actor -> {
            service.requireInvitationAccess(actor, request.tenantId(), request.scope());
            String name = request.account().strip();
            return identities.findInvitationPrincipal(name)
                    .map(value -> new InvitationTarget(value.subjectId(), name))
                    .orElseThrow(() -> new PlatformException(PlatformErrorCodeEnum.NOT_FOUND, "invitation-target"));
        });
    }

    @PostMapping("/listTenantMembers")
    public Mono<ResultContext<PageResult<MemberRow>>> tenantMembers(Authentication auth, @Valid @RequestBody TenantPage request, Locale locale) {
        return call(auth, locale, actor -> service.listMembers(actor, request.tenantId(), page(request.current(), request.size()))
                .map(v -> new MemberRow(v.memberId(), v.subjectId(), name(v.subjectId()), v.role().getValue(), v.state().getValue(), v.version())));
    }

    @PostMapping("/listWorkspaceMembers")
    public Mono<ResultContext<PageResult<MemberRow>>> workspaceMembers(Authentication auth, @Valid @RequestBody SpacePage request, Locale locale) {
        return call(auth, locale, actor -> service.listMembers(actor, request.scope(), page(request.current(), request.size()))
                .map(v -> new MemberRow(v.memberId(), v.subjectId(), name(v.subjectId()), v.role().getValue(), v.state().getValue(), v.version())));
    }

    private InvitationRow tenantRow(TenantInvitationView view, boolean inbox) {
        return new InvitationRow(view.invitationId(), view.tenantId(), null, view.targetSubjectId(),
                inbox ? null : name(view.targetSubjectId()),
                inbox ? tenants.find(view.tenantId()).map(Tenant::name).orElse(null) : null,
                null, view.state().getValue(), view.expiresAt(), view.version());
    }

    private InvitationRow workspaceRow(InvitationView view, boolean inbox) {
        return new InvitationRow(view.invitationId(), null, view.scope(), view.targetSubjectId(),
                inbox ? null : name(view.targetSubjectId()),
                inbox ? workspaces.find(view.scope()).map(Workspace::name).orElse(null) : null,
                view.role().getValue(), view.state().getValue(), view.expiresAt(), view.version());
    }

    @PostMapping("/listTenantInvitations")
    public Mono<ResultContext<PageResult<InvitationRow>>> tenantInvitations(Authentication auth, @Valid @RequestBody TenantPage request, Locale locale) {
        return call(auth, locale, actor -> service.listTenantInvitations(actor, request.tenantId(), page(request.current(), request.size())).map(v -> tenantRow(v, false)));
    }

    @PostMapping("/listWorkspaceInvitations")
    public Mono<ResultContext<PageResult<InvitationRow>>> workspaceInvitations(Authentication auth, @Valid @RequestBody SpacePage request, Locale locale) {
        return call(auth, locale, actor -> service.listWorkspaceInvitations(actor, request.scope(), page(request.current(), request.size())).map(v -> workspaceRow(v, false)));
    }

    @PostMapping("/listMyTenantInvitations")
    public Mono<ResultContext<PageResult<InvitationRow>>> myTenantInvitations(Authentication auth, @Valid @RequestBody Page request, Locale locale) {
        return call(auth, locale, actor -> service.listMyTenantInvitations(actor, page(request.current(), request.size())).map(v -> tenantRow(v, true)));
    }

    @PostMapping("/listMyWorkspaceInvitations")
    public Mono<ResultContext<PageResult<InvitationRow>>> myWorkspaceInvitations(Authentication auth, @Valid @RequestBody Page request, Locale locale) {
        return call(auth, locale, actor -> service.listInvitations(actor, page(request.current(), request.size())).map(v -> workspaceRow(v, true)));
    }

    @PostMapping("/inviteTenantMember")
    public Mono<ResultContext<CommandReceipt<TenantInvitationView>>> inviteTenantMember(Authentication auth,
                                                                                        @Valid @RequestBody TenantInvite request, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        TenantCommands.InviteTenantMember command = new TenantCommands.InviteTenantMember(new CommandMeta(key, PlatformHttpContext.traceId(http)),
                request.tenantId(), request.expectedTenantVersion(), request.targetSubjectId(), request.expiresAt());
        return call(auth, locale, actor -> service.inviteTenantMember(actor, command));
    }

    @PostMapping("/inviteWorkspaceMember")
    public Mono<ResultContext<CommandReceipt<InvitationView>>> inviteWorkspaceMember(Authentication auth,
                                                                                     @Valid @RequestBody SpaceInvite request, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        WorkspaceCommands.InviteMember command = new WorkspaceCommands.InviteMember(new CommandMeta(key, PlatformHttpContext.traceId(http)),
                request.scope(), request.expectedWorkspaceVersion(), request.targetSubjectId(),
                WorkspaceRoleEnum.values()[request.role()], request.expiresAt());
        return call(auth, locale, actor -> service.invite(actor, command));
    }

    @PostMapping("/revokeTenantInvitation")
    public Mono<ResultContext<CommandReceipt<TenantInvitationView>>> revokeTenantInvitation(Authentication auth,
                                                                                            @Valid @RequestBody TenantRevoke request, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        TenantCommands.RevokeTenantInvitation command = new TenantCommands.RevokeTenantInvitation(new CommandMeta(key, PlatformHttpContext.traceId(http)),
                request.tenantId(), request.expectedTenantVersion(), request.invitationId(),
                request.expectedInvitationVersion());
        return call(auth, locale, actor -> service.revokeTenantInvitation(actor, command));
    }

    @PostMapping("/revokeWorkspaceInvitation")
    public Mono<ResultContext<CommandReceipt<InvitationView>>> revokeWorkspaceInvitation(Authentication auth,
                                                                                         @Valid @RequestBody SpaceRevoke request, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        WorkspaceCommands.RevokeInvitation command = new WorkspaceCommands.RevokeInvitation(new CommandMeta(key, PlatformHttpContext.traceId(http)),
                request.scope(), request.invitationId(), request.expectedInvitationVersion(),
                request.expectedWorkspaceVersion());
        return call(auth, locale, actor -> service.revokeInvitation(actor, command));
    }

    @PostMapping("/acceptTenantInvitation")
    public Mono<ResultContext<CommandReceipt<TenantJoinResult>>> acceptTenantInvitation(Authentication auth,
                                                                                        @Valid @RequestBody Accept request, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        TenantCommands.AcceptTenantInvitation command = new TenantCommands.AcceptTenantInvitation(new CommandMeta(key, PlatformHttpContext.traceId(http)),
                request.invitationId(), request.expectedInvitationVersion());
        return call(auth, locale, actor -> service.acceptTenantInvitation(actor, command));
    }

    @PostMapping("/acceptWorkspaceInvitation")
    public Mono<ResultContext<CommandReceipt<JoinResult>>> acceptWorkspaceInvitation(Authentication auth,
                                                                                     @Valid @RequestBody Accept request, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        WorkspaceCommands.AcceptInvitation command = new WorkspaceCommands.AcceptInvitation(new CommandMeta(key, PlatformHttpContext.traceId(http)),
                request.invitationId(), request.expectedInvitationVersion());
        return call(auth, locale, actor -> service.accept(actor, command));
    }

    @PostMapping("/changeTenantMemberRole")
    public Mono<ResultContext<CommandReceipt<TenantMemberView>>> changeTenantMemberRole(Authentication auth,
                                                                                        @Valid @RequestBody TenantRole request, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        TenantCommands.ChangeTenantMemberRole command = new TenantCommands.ChangeTenantMemberRole(new CommandMeta(key, PlatformHttpContext.traceId(http)),
                request.tenantId(), request.memberId(), request.expectedMemberVersion(),
                request.expectedTenantVersion(), TenantRoleEnum.values()[request.role()]);
        return call(auth, locale, actor -> service.changeMemberRole(actor, command));
    }

    @PostMapping("/changeWorkspaceMemberRole")
    public Mono<ResultContext<CommandReceipt<MemberView>>> changeWorkspaceMemberRole(Authentication auth,
                                                                                     @Valid @RequestBody SpaceRole request, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        WorkspaceCommands.ChangeMemberRole command = new WorkspaceCommands.ChangeMemberRole(new CommandMeta(key, PlatformHttpContext.traceId(http)),
                request.scope(), request.memberId(), request.expectedMemberVersion(),
                request.expectedWorkspaceVersion(), WorkspaceRoleEnum.values()[request.role()]);
        return call(auth, locale, actor -> service.changeRole(actor, command));
    }

    @PostMapping("/removeTenantMember")
    public Mono<ResultContext<CommandReceipt<Void>>> removeTenantMember(Authentication auth,
                                                                        @Valid @RequestBody TenantRemove request, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        TenantCommands.RemoveTenantMember command = new TenantCommands.RemoveTenantMember(new CommandMeta(key, PlatformHttpContext.traceId(http)),
                request.tenantId(), request.memberId(), request.expectedMemberVersion(),
                request.expectedTenantVersion());
        return call(auth, locale, actor -> service.removeMember(actor, command));
    }

    @PostMapping("/removeWorkspaceMember")
    public Mono<ResultContext<CommandReceipt<Void>>> removeWorkspaceMember(Authentication auth,
                                                                           @Valid @RequestBody SpaceRemove request, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        WorkspaceCommands.RemoveMember command = new WorkspaceCommands.RemoveMember(new CommandMeta(key, PlatformHttpContext.traceId(http)),
                request.scope(), request.memberId(), request.expectedMemberVersion(),
                request.expectedWorkspaceVersion());
        return call(auth, locale, actor -> service.removeMember(actor, command));
    }

    @PostMapping("/leaveWorkspace")
    public Mono<ResultContext<CommandReceipt<Void>>> leaveWorkspace(Authentication auth,
                                                                    @Valid @RequestBody Leave request, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        WorkspaceCommands.LeaveWorkspace command = new WorkspaceCommands.LeaveWorkspace(new CommandMeta(key, PlatformHttpContext.traceId(http)),
                request.scope(), request.expectedMemberVersion(), request.expectedWorkspaceVersion());
        return call(auth, locale, actor -> service.leave(actor, command));
    }

    @PostMapping("/transferTenantOwner")
    public Mono<ResultContext<CommandReceipt<TenantView>>> transferTenantOwner(Authentication auth,
                                                                               @Valid @RequestBody TenantTransfer request, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        TenantCommands.TransferTenantOwner command = new TenantCommands.TransferTenantOwner(new CommandMeta(key, PlatformHttpContext.traceId(http)),
                request.tenantId(), request.targetMemberId(), request.expectedOwnerMemberVersion(),
                request.expectedTargetMemberVersion(), request.expectedTenantVersion());
        return call(auth, locale, actor -> service.transferOwner(actor, command));
    }

    @PostMapping("/transferWorkspaceOwner")
    public Mono<ResultContext<CommandReceipt<WorkspaceView>>> transferWorkspaceOwner(Authentication auth,
                                                                                     @Valid @RequestBody SpaceTransfer request, @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        WorkspaceCommands.TransferOwner command = new WorkspaceCommands.TransferOwner(new CommandMeta(key, PlatformHttpContext.traceId(http)),
                request.scope(), request.targetMemberId(), request.expectedOwnerMemberVersion(),
                request.expectedTargetMemberVersion(), request.expectedWorkspaceVersion());
        return call(auth, locale, actor -> service.transferOwner(actor, command));
    }
}
