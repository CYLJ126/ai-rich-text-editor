package com.arte.app.web.platform;

import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.*;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 公共资源 ACL 的显式 HTTP 目标、接收方与展示视图，不绑定内部授权证明。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:00 ✾
 */
public final class ResourceAccessPayload {

    private ResourceAccessPayload() {
    }

    /**
     * 仅用于定位；租户从 scope 派生，真实归属仍由领域提供者核验。
     */
    public record Target(@NotNull Scope scope, @NotBlank @Size(max = 96) String resourceType,
                         @NotBlank @Size(max = 256) String resourceId) implements StrictPlatformRequest {
        public ResourceRef ref() {
            return new ResourceRef(scope.tenantId(), new ResourceType(resourceType), resourceId);
        }

        public AccessRequest request(Action action) {
            return new AccessRequest(scope, ref(), action, "http-resource");
        }
    }

    public record GetRequest(@NotNull @Valid Target target) implements StrictPlatformRequest {
    }

    public record ListRequest(@NotNull @Valid Target target, @Min(1) Long current,
                              @Min(1) @Max(100) Long size) implements StrictPlatformRequest {
        public ListRequest {
            current = current == null ? 1L : current;
            size = size == null ? 20L : size;
        }
    }

    /**
     * SUBJECT 只接受主体 ID；SPACE_ROLE 只接受角色；空间范围一律由 target.scope 派生。
     */
    public record RecipientRequest(@NotBlank @Pattern(regexp = "SUBJECT|SPACE_ROLE|SPACE") String kind,
                                   @Size(max = 256) String subjectId,
                                   @Pattern(regexp = "OWNER|ADMIN|MEMBER|GUEST") String role) implements StrictPlatformRequest {
        public GrantRecipient recipient(Scope scope) {
            return switch (kind) {
                case "SUBJECT" -> {
                    PlatformChecks.require(role == null, "Subject recipient cannot specify role");
                    yield new GrantRecipient.Subject(subjectId);
                }
                case "SPACE_ROLE" -> {
                    PlatformChecks.require(subjectId == null && role != null, "Role recipient requires only role");
                    yield new GrantRecipient.SpaceRole(scope, WorkspaceRoleEnum.valueOf(role));
                }
                case "SPACE" -> {
                    PlatformChecks.require(subjectId == null && role == null, "Space recipient cannot specify subject or role");
                    yield new GrantRecipient.Space(scope);
                }
                default -> throw new IllegalArgumentException("Unknown recipient kind");
            };
        }
    }

    public record GrantRequest(@NotNull @Valid Target target, @NotNull @Min(0) Long expectedAclVersion,
                               @NotNull @Valid RecipientRequest recipient,
                               @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 96) String> actions,
                               Instant expiresAt) implements StrictPlatformRequest {
    }

    public record RevokeRequest(@NotNull @Valid Target target, @NotBlank @Size(max = 256) String grantId,
                                @NotNull @Min(0) Long expectedAclVersion,
                                @NotNull @Min(0) Long expectedGrantVersion) implements StrictPlatformRequest {
    }

    public record SharedGrant(@NotNull @Valid RecipientRequest recipient,
                              @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 96) String> actions,
                              Instant expiresAt) implements StrictPlatformRequest {
        public VisibilityGrant toGrant(Scope scope) {
            return new VisibilityGrant(recipient.recipient(scope), actionSet(actions), expiresAt);
        }
    }

    public record VisibilityRequest(@NotNull @Valid Target target, @NotNull @Min(0) Long expectedAclVersion,
                                    @NotBlank @Pattern(regexp = "EXPLICIT|SPACE_SHARED") String visibility,
                                    @NotNull @Size(max = 100) List<@NotNull @Valid SharedGrant> sharedGrants) implements StrictPlatformRequest {
    }

    public record CheckRequest(@NotNull @Valid Target target,
                               @NotBlank @Size(max = 96) String action) implements StrictPlatformRequest {
        public AccessRequest request() {
            return target.request(new Action(action));
        }
    }

    public record BatchRequest(
            @NotEmpty @Size(max = 100) List<@NotNull @Valid CheckRequest> requests) implements StrictPlatformRequest {
    }

    public record Access(ResourceRef resource, ResourceOwnership ownership, String visibility, long aclVersion) {
        public static Access from(ResourceAccessViews.AccessView value) {
            return new Access(value.resource(), value.ownership(), value.visibility().name(), value.aclVersion());
        }
    }

    /**
     * 显式标明接收方类型；scope 只出现在服务器生成的空间／角色接收方视图。
     */
    public record Recipient(String kind, String subjectId, Scope scope, String role) {
        static Recipient from(GrantRecipient value) {
            return switch (value) {
                case GrantRecipient.Subject subject -> new Recipient("SUBJECT", subject.subjectId(), null, null);
                case GrantRecipient.SpaceRole role ->
                        new Recipient("SPACE_ROLE", null, role.scope(), role.role().name());
                case GrantRecipient.Space space -> new Recipient("SPACE", null, space.scope(), null);
            };
        }
    }

    public record Grant(String id, ResourceRef resource, Recipient recipient, String action, String state,
                        Instant expiresAt, String source, long version) {
        public static Grant from(ResourceAccessViews.GrantView value) {
            return new Grant(value.id(), value.resource(), Recipient.from(value.recipient()), value.action().value(),
                    value.state().name(), value.expiresAt(), value.source().name(), value.version());
        }
    }

    public record GrantResult(List<Grant> grants, long aclVersion) {
        public static GrantResult from(ResourceAccessViews.GrantResult value) {
            return new GrantResult(value.grants().stream().map(Grant::from).toList(), value.aclVersion());
        }
    }

    /**
     * 仅供展示，不暴露公共授权版本，不作为写入许可。
     */
    public record CheckResult(Target target, String action, boolean allowed, String reason, Instant checkedAt) {
        public static CheckResult from(CheckRequest request, AccessDecision value) {
            if (value.reason() == AccessReasonEnum.AUTHORITY_UNAVAILABLE) {
                throw new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, "resource-access");
            }
            if (value.reason() == AccessReasonEnum.SUBJECT_INACTIVE) {
                throw new PlatformException(PlatformErrorCodeEnum.UNAUTHENTICATED, "resource-access");
            }
            String reason = value.reason() == AccessReasonEnum.MEMBERSHIP_REQUIRED ? "NOT_FOUND" : value.reason().name();
            return new CheckResult(request.target(), request.action(), value.allowed(), reason, value.checkedAt());
        }
    }

    public static Set<Action> actionSet(List<String> values) {
        PlatformChecks.require(new HashSet<>(values).size() == values.size(), "Duplicate action");
        return values.stream().map(Action::new).collect(Collectors.toUnmodifiableSet());
    }
}
